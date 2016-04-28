package org.eclipse.jetty.http2;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;

import javax.servlet.AsyncContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.log4j.Logger;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

public class HTTP2Servlet extends javax.servlet.http.HttpServlet {
    private final Logger logger = Logger.getLogger(HTTP2Servlet.class);
    private final SslContextFactory sslContextFactory = new SslContextFactory();
    private final HTTP2Client client = new HTTP2Client();

    @Override
    public void init() throws ServletException {
        try {
            sslContextFactory.start();

            QueuedThreadPool threads = new QueuedThreadPool();
            threads.setName("client");
            client.setExecutor(threads);
            client.setSelectors(1);
            client.start();

            logger.info(String.format("Created %s", client));
        } catch (Exception x) {
            throw new ServletException(x);
        }
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        logger.info(String.format("Request %s", request.getRequestURI()));

        AsyncContext asyncContext = request.startAsync();
        asyncContext.setTimeout(0);

        String host = "webtide.com";
        int port = 443;
        InetAddress address = InetAddress.getByName(host);
        client.connect(sslContextFactory, new InetSocketAddress(address, port), new ServerSessionListener.Adapter(), new Promise<Session>() {
            @Override
            public void succeeded(Session session) {
                logger.info(String.format("Connect successful, session=%s", session));

                HttpURI uri = new HttpURI("https://" + host + ":" + port + "/");
                MetaData.Request metaData = new MetaData.Request("GET", uri, HttpVersion.HTTP_2, new HttpFields());
                HeadersFrame frame = new HeadersFrame(metaData, null, true);
                session.newStream(frame, new Promise<Stream>() {
                    @Override
                    public void succeeded(Stream stream) {
                        logger.info(String.format("Stream successful, stream=%s", stream));
                    }

                    @Override
                    public void failed(Throwable failure) {
                        logger.info("Stream failed", failure);
                        response.setStatus(500);
                        asyncContext.complete();
                    }
                }, new Stream.Listener.Adapter() {
                    @Override
                    public void onHeaders(Stream stream, HeadersFrame frame) {
                        int status = ((MetaData.Response)frame.getMetaData()).getStatus();
                        logger.info(String.format("Stream response, status=%d", status));
                        response.setStatus(status);
                    }

                    @Override
                    public void onData(Stream stream, DataFrame frame, Callback callback) {
                        logger.info(String.format("Stream response content, bytes=%d", frame.getData().remaining()));
                        callback.succeeded();
                        if (frame.isEndStream()) {
                            logger.info("Stream response complete");
                            asyncContext.complete();
                        }
                    }
                });
            }

            @Override
            public void failed(Throwable failure) {
                logger.info("Connect failed", failure);
                response.setStatus(500);
                asyncContext.complete();
            }
        });
    }
}
