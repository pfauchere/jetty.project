//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.server.handler;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.channels.SocketChannel;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.server.ssl.SslSelectChannelConnector;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

public class ConnectHandlerSSLTest extends AbstractConnectHandlerTest
{
    @Before
    public void init() throws Exception
    {
        startServer(prepareServerConnector(), new ServerHandler());
        startProxy();
    }

    private SslSelectChannelConnector prepareServerConnector()
    {
        SslSelectChannelConnector connector = new SslSelectChannelConnector();
        String keyStorePath = MavenTestingUtils.getTestResourceFile("keystore").getAbsolutePath();
        SslContextFactory cf = connector.getSslContextFactory();
        cf.setKeyStorePath(keyStorePath);
        cf.setKeyStorePassword("storepwd");
        cf.setKeyManagerPassword("keypwd");
        return connector;
    }

    @Test
    public void testGETRequest() throws Exception
    {
        String hostPort = "localhost:" + serverConnector.getLocalPort();
        String request = "" +
                "CONNECT " + hostPort + " HTTP/1.1\r\n" +
                "Host: " + hostPort + "\r\n" +
                "\r\n";
        Socket socket = newSocket();
        try
        {
            OutputStream output = socket.getOutputStream();
            BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            output.write(request.getBytes("UTF-8"));
            output.flush();

            // Expect 200 OK from the CONNECT request
            Response response = readResponse(input);
            System.err.println(response);
            assertEquals("200", response.getCode());

            // Be sure the buffered input does not have anything buffered
            assertFalse(input.ready());

            // Upgrade the socket to SSL
            SSLSocket sslSocket = wrapSocket(socket);
            try
            {
                output = sslSocket.getOutputStream();
                input = new BufferedReader(new InputStreamReader(sslSocket.getInputStream()));

                request =
                        "GET /echo HTTP/1.1\r\n" +
                                "Host: " + hostPort + "\r\n" +
                                "\r\n";
                output.write(request.getBytes("UTF-8"));
                output.flush();

                response = readResponse(input);
                assertEquals("200", response.getCode());
                assertEquals("GET /echo", response.getBody());
            }
            finally
            {
                sslSocket.close();
            }
        }
        finally
        {
            socket.close();
        }
    }

    @Test
    public void testPOSTRequests() throws Exception
    {
        String hostPort = "localhost:" + serverConnector.getLocalPort();
        String request = "" +
                "CONNECT " + hostPort + " HTTP/1.1\r\n" +
                "Host: " + hostPort + "\r\n" +
                "\r\n";
        Socket socket = newSocket();
        try
        {
            OutputStream output = socket.getOutputStream();
            BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            output.write(request.getBytes("UTF-8"));
            output.flush();

            // Expect 200 OK from the CONNECT request
            Response response = readResponse(input);
            assertEquals("200", response.getCode());

            // Be sure the buffered input does not have anything buffered
            assertFalse(input.ready());

            // Upgrade the socket to SSL
            SSLSocket sslSocket = wrapSocket(socket);
            try
            {
                output = sslSocket.getOutputStream();
                input = new BufferedReader(new InputStreamReader(sslSocket.getInputStream()));

                for (int i = 0; i < 10; ++i)
                {
                    request = "" +
                            "POST /echo?param=" + i + " HTTP/1.1\r\n" +
                            "Host: " + hostPort + "\r\n" +
                            "Content-Length: 5\r\n" +
                            "\r\n" +
                            "HELLO";
                    output.write(request.getBytes("UTF-8"));
                    output.flush();

                    response = readResponse(input);
                    assertEquals("200", response.getCode());
                    assertEquals("POST /echo?param=" + i + "\r\nHELLO", response.getBody());
                }
            }
            finally
            {
                sslSocket.close();
            }
        }
        finally
        {
            socket.close();
        }
    }

    @Test
    public void testServerHalfClosesClientDoesNotCloseExpectIdleTimeout() throws Exception
    {
        stop();

        final String uri = "/echo";
        int idleTimeout = 2000;
        startServer(prepareServerConnector(), new AbstractHandler()
        {
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                String requestURI = request.getRequestURI();
                if (uri.equals(requestURI))
                {
                    baseRequest.setHandled(true);
                    response.setHeader("Connection", "close");
                    response.setContentLength(uri.length());
                    response.getOutputStream().print(uri);
                }
            }
        });
        Connector proxyConnector = new SelectChannelConnector();
        proxyConnector.setMaxIdleTime(idleTimeout);

        final AtomicReference<EndPoint> proxyToClientEndPoint = new AtomicReference<EndPoint>();
        final AtomicReference<EndPoint> proxyToServerEndPoint = new AtomicReference<EndPoint>();
        ConnectHandler connectHandler = new ConnectHandler()
        {
            @Override
            protected ClientToProxyConnection newClientToProxyConnection(ConcurrentMap<String, Object> context, SocketChannel channel, EndPoint endPoint, long timeStamp)
            {
                proxyToClientEndPoint.set(endPoint);
                return new ClientToProxyConnection(context, channel, endPoint, timeStamp);
            }

            @Override
            protected ProxyToServerConnection newProxyToServerConnection(ConcurrentMap<String, Object> context, Buffer buffer)
            {
                return new ProxyToServerConnection(context, buffer)
                {
                    @Override
                    public void setEndPoint(AsyncEndPoint endpoint)
                    {
                        proxyToServerEndPoint.set(endpoint);
                        super.setEndPoint(endpoint);
                    }
                };
            }
        };
        connectHandler.setWriteTimeout(2 * idleTimeout);
        startProxy(proxyConnector, connectHandler);

        String hostPort = "localhost:" + serverConnector.getLocalPort();
        String request = "" +
                "CONNECT " + hostPort + " HTTP/1.1\r\n" +
                "Host: " + hostPort + "\r\n" +
                "\r\n";
        Socket socket = newSocket();
        try
        {
            OutputStream output = socket.getOutputStream();
            BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            output.write(request.getBytes("UTF-8"));
            output.flush();

            // Expect 200 OK from the CONNECT request
            Response response = readResponse(input);
            System.err.println(response);
            assertEquals("200", response.getCode());

            // Be sure the buffered input does not have anything buffered
            assertFalse(input.ready());

            // Upgrade the socket to SSL
            SSLSocket sslSocket = wrapSocket(socket);
            try
            {
                output = sslSocket.getOutputStream();
                input = new BufferedReader(new InputStreamReader(sslSocket.getInputStream()));

                request = "" +
                        "GET " + uri + " HTTP/1.1\r\n" +
                        "Host: " + hostPort + "\r\n" +
                        "\r\n";
                output.write(request.getBytes("UTF-8"));
                output.flush();

                response = readResponse(input);
                assertEquals("200", response.getCode());
                assertEquals(uri, response.getBody());

                Thread.sleep(4 * idleTimeout);

                EndPoint p2c = proxyToClientEndPoint.get();
                assertNotNull(p2c);
                assertFalse(p2c.isOpen());
                EndPoint p2s = proxyToServerEndPoint.get();
                assertNotNull(p2s);
                assertFalse(p2s.isOpen());
            }
            finally
            {
                sslSocket.close();
            }
        }
        finally
        {
            socket.close();
        }
    }

    private SSLSocket wrapSocket(Socket socket) throws Exception
    {
        SSLContext sslContext = SSLContext.getInstance("SSLv3");
        sslContext.init(null, new TrustManager[]{new AlwaysTrustManager()}, new SecureRandom());
        SSLSocketFactory socketFactory = sslContext.getSocketFactory();
        SSLSocket sslSocket = (SSLSocket)socketFactory.createSocket(socket, socket.getInetAddress().getHostAddress(), socket.getPort(), true);
        sslSocket.setUseClientMode(true);
        sslSocket.startHandshake();
        return sslSocket;
    }

    private class AlwaysTrustManager implements X509TrustManager
    {
        public void checkClientTrusted(X509Certificate[] x509Certificates, String s) throws CertificateException
        {
        }

        public void checkServerTrusted(X509Certificate[] x509Certificates, String s) throws CertificateException
        {
        }

        public X509Certificate[] getAcceptedIssuers()
        {
            return new X509Certificate[]{};
        }
    }

    private static class ServerHandler extends AbstractHandler
    {
        public void handle(String target, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException, ServletException
        {
            request.setHandled(true);

            String uri = httpRequest.getRequestURI();
            if ("/echo".equals(uri))
            {
                StringBuilder builder = new StringBuilder();
                builder.append(httpRequest.getMethod()).append(" ").append(uri);
                if (httpRequest.getQueryString() != null)
                    builder.append("?").append(httpRequest.getQueryString());

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                InputStream input = httpRequest.getInputStream();
                int read;
                while ((read = input.read()) >= 0)
                    baos.write(read);
                baos.close();

                ServletOutputStream output = httpResponse.getOutputStream();
                output.println(builder.toString());
                output.write(baos.toByteArray());
            }
            else
            {
                throw new ServletException();
            }
        }
    }
}
