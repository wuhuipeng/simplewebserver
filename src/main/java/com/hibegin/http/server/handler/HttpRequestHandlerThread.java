package com.hibegin.http.server.handler;

import com.hibegin.common.util.LoggerUtil;
import com.hibegin.http.server.api.HttpRequest;
import com.hibegin.http.server.api.HttpRequestListener;
import com.hibegin.http.server.api.HttpResponse;
import com.hibegin.http.server.api.Interceptor;
import com.hibegin.http.server.impl.HttpMethod;
import com.hibegin.http.server.impl.ServerContext;

import java.net.Socket;
import java.nio.channels.SocketChannel;
import java.util.logging.Level;
import java.util.logging.Logger;

public class HttpRequestHandlerThread extends Thread {

    private static final Logger LOGGER = LoggerUtil.getLogger(HttpRequestHandlerThread.class);

    private HttpRequest request;
    private ServerContext serverContext;

    private HttpResponse response;
    private SocketChannel channel;
    private boolean interrupted;

    public HttpRequestHandlerThread(HttpRequest request, HttpResponse response, ServerContext serverContext) {
        this.serverContext = serverContext;
        this.request = request;
        this.response = response;
        this.channel = request.getHandler().getChannel();
    }

    @Override
    public void run() {
        try {
            if (!serverContext.getServerConfig().getHttpRequestListenerList().isEmpty()) {
                for (HttpRequestListener httpRequestListener : serverContext.getServerConfig().getHttpRequestListenerList()) {
                    httpRequestListener.create(request, response);
                }
            }
            for (Interceptor interceptor : serverContext.getInterceptors()) {
                if (!interceptor.doInterceptor(request, response)) {
                    break;
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "dispose error ", e);
        } finally {
            if (request.getMethod() != HttpMethod.CONNECT) {
                boolean keepAlive = request.getHeader("Connection") != null && "keep-alive".equalsIgnoreCase(request.getHeader("Connection"));
                if (keepAlive) {
                    keepAlive = response.getHeader().get("Connection") != null && !"close".equalsIgnoreCase(response.getHeader().get("Connection"));
                }
                if (!keepAlive) {
                    Socket socket = channel.socket();
                    // 渲染错误页面
                    if (!socket.isClosed()) {
                        LOGGER.log(Level.WARNING, "forget close stream " + socket.toString());
                        response.renderCode(404);
                    }
                }
                serverContext.getHttpDeCoderMap().remove(channel);
                close();
            }
        }
    }

    public HttpRequest getRequest() {
        return request;
    }

    public HttpResponse getResponse() {
        return response;
    }

    @Override
    public void interrupt() {
        synchronized (this) {
            if (!interrupted) {
                close();
            }
        }
        super.interrupt();
    }

    private void close() {
        interrupted = true;
        LOGGER.info(request.getMethod() + ": " + request.getUrl() + " " + (System.currentTimeMillis() - request.getCreateTime()) + " ms");
        if (channel.socket().isClosed()) {
            serverContext.getHttpDeCoderMap().remove(channel);
        }
        for (HttpRequestListener requestListener : serverContext.getServerConfig().getHttpRequestListenerList()) {
            requestListener.destroy(this.getRequest(), this.getResponse());
        }
    }
}