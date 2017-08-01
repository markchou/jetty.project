package org.eclipse.jetty.websocket.jsr356;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.websocket.CloseReason;
import javax.websocket.Decoder;
import javax.websocket.Encoder;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.Extension;
import javax.websocket.MessageHandler;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.RemoteEndpoint;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.jetty.http.pathmap.UriTemplatePathSpec;
import org.eclipse.jetty.util.BufferUtil;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

public class EndpointInvokerTest
{
    private static final Map<String,String> parameters = new HashMap<>();
    private static final List<Object> history = new ArrayList<>();
    private static final EndpointConfig CONFIG = new EndpointConfig()
    {
        @Override
        public List<Class<? extends Encoder>> getEncoders()
        {
            return null;
        }

        @Override
        public List<Class<? extends Decoder>> getDecoders()
        {
            return null;
        }

        @Override
        public Map<String, Object> getUserProperties()
        {
            return null;
        }
    };

    public static EndpointInvoker endpointInvoker;

    public static final Session SESSION = new Session()
    {
        @Override
        public WebSocketContainer getContainer()
        {
            return null;
        }

        @Override
        public void addMessageHandler(MessageHandler handler) throws IllegalStateException
        {

        }

        @Override
        public <T> void addMessageHandler(Class<T> clazz, MessageHandler.Whole<T> handler)
        {
            endpointInvoker.addMessageHandler(clazz,handler);
        }

        @Override
        public <T> void addMessageHandler(Class<T> clazz, MessageHandler.Partial<T> handler)
        {
            endpointInvoker.addMessageHandler(clazz,handler);
        }

        @Override
        public Set<MessageHandler> getMessageHandlers()
        {
            return null;
        }

        @Override
        public void removeMessageHandler(MessageHandler handler)
        {

        }

        @Override
        public String getProtocolVersion()
        {
            return null;
        }

        @Override
        public String getNegotiatedSubprotocol()
        {
            return null;
        }

        @Override
        public List<Extension> getNegotiatedExtensions()
        {
            return null;
        }

        @Override
        public boolean isSecure()
        {
            return false;
        }

        @Override
        public boolean isOpen()
        {
            return false;
        }

        @Override
        public long getMaxIdleTimeout()
        {
            return 0;
        }

        @Override
        public void setMaxIdleTimeout(long milliseconds)
        {

        }

        @Override
        public void setMaxBinaryMessageBufferSize(int length)
        {

        }

        @Override
        public int getMaxBinaryMessageBufferSize()
        {
            return 0;
        }

        @Override
        public void setMaxTextMessageBufferSize(int length)
        {

        }

        @Override
        public int getMaxTextMessageBufferSize()
        {
            return 0;
        }

        @Override
        public RemoteEndpoint.Async getAsyncRemote()
        {
            return null;
        }

        @Override
        public RemoteEndpoint.Basic getBasicRemote()
        {
            return null;
        }

        @Override
        public String getId()
        {
            return null;
        }

        @Override
        public void close() throws IOException
        {

        }

        @Override
        public void close(CloseReason closeReason) throws IOException
        {

        }

        @Override
        public URI getRequestURI()
        {
            return null;
        }

        @Override
        public Map<String, List<String>> getRequestParameterMap()
        {
            return null;
        }

        @Override
        public String getQueryString()
        {
            return null;
        }

        @Override
        public Map<String, String> getPathParameters()
        {
            return null;
        }

        @Override
        public Map<String, Object> getUserProperties()
        {
            return null;
        }

        @Override
        public Principal getUserPrincipal()
        {
            return null;
        }

        @Override
        public Set<Session> getOpenSessions()
        {
            return null;
        }
    };

    @BeforeClass
    public static void beforeClass()
    {
        parameters.put("one","1");
        parameters.put("two","2");
        parameters.put("three","3");
    }

    @Before
    public void before()
    {
        history.clear();
    }

    public static class Empty
    {
    }

    @Test
    public void testEmpty()
    {
        Empty endp = new Empty();
        UriTemplatePathSpec pathSpec = new UriTemplatePathSpec("/test");
        EndpointInvoker invoker = new EndpointInvoker.Builder(Empty.class,pathSpec).build(endp,SESSION,parameters);

        invoker.onOpen(null);
        invoker.onError(null);
        invoker.onClose(null);
    }

    public static class DefaultEndpoint extends Endpoint
    {
        @Override
        public void onOpen(Session session, EndpointConfig config)
        {
            history.add("onOpen");
            history.add(session);
            history.add(config);
        }
    }

    @Test
    public void testDefaultEndpoint()
    {
        DefaultEndpoint endp = new DefaultEndpoint();
        UriTemplatePathSpec pathSpec = new UriTemplatePathSpec("/test");
        EndpointInvoker invoker = new EndpointInvoker.Builder(DefaultEndpoint.class,pathSpec).build(endp,SESSION,parameters);

        invoker.onOpen(CONFIG);
        invoker.onError(null);
        invoker.onClose(null);

        assertThat(history.size(), is(3));
        assertThat(history,Matchers.contains("onOpen",SESSION,CONFIG));
    }


    public static class TestEndpoint extends Endpoint
    {
        @Override
        public void onOpen(Session session, EndpointConfig config)
        {
            history.add("onOpen");
            history.add(session);
            history.add(config);
        }

        @Override
        public void onClose(Session session, CloseReason closeReason)
        {
            history.add("onClose");
            history.add(session);
            history.add(closeReason);
        }

        @Override
        public void onError(Session session, Throwable thr)
        {
            history.add("onError");
            history.add(session);
            history.add(thr);
        }
    }

    @Test
    public void testTestEndpoint()
    {
        TestEndpoint endp = new TestEndpoint();
        UriTemplatePathSpec pathSpec = new UriTemplatePathSpec("/test");
        EndpointInvoker invoker = new EndpointInvoker.Builder(TestEndpoint.class,pathSpec).build(endp,SESSION,parameters);

        Exception ex = new Exception();
        CloseReason reason = new CloseReason(CloseReason.CloseCodes.NO_STATUS_CODE,"because");

        invoker.onOpen(CONFIG);
        invoker.onError(ex);
        invoker.onClose(reason);

        assertThat(history.size(), is(9));
        assertThat(history,Matchers.contains(
                "onOpen",SESSION,CONFIG,
                "onError",SESSION,ex,
                "onClose",SESSION,reason));
    }


    @ServerEndpoint("/test")
    public static class AnnotatedEndpoint
    {
        @OnOpen
        public void onAnnotatedOpen(EndpointConfig config)
        {
            history.add("onOpen");
            history.add(SESSION);
            history.add(config);
        }

        @OnClose
        public void onAnnotatedClose(CloseReason closeReason,Session session)
        {
            history.add("onClose");
            history.add(session);
            history.add(closeReason);
        }

        @OnError
        public void onAnnotatedError(Session session, Throwable thr)
        {
            history.add("onError");
            history.add(session);
            history.add(thr);
        }
    }

    @Test
    public void testAnnotatedEndpoint()
    {
        AnnotatedEndpoint endp = new AnnotatedEndpoint();
        EndpointInvoker invoker = new EndpointInvoker.Builder(AnnotatedEndpoint.class).build(endp,SESSION,parameters);

        Exception ex = new Exception();
        CloseReason reason = new CloseReason(CloseReason.CloseCodes.NO_STATUS_CODE,"because");

        invoker.onOpen(CONFIG);
        invoker.onError(ex);
        invoker.onClose(reason);

        assertThat(history.size(), is(9));
        assertThat(history,Matchers.contains(
                "onOpen",SESSION,CONFIG,
                "onError",SESSION,ex,
                "onClose",SESSION,reason));
    }


    @ServerEndpoint("/test/{one}/{two}/{three}")
    public static class AnnotatedParamEndpoint
    {
        @OnOpen
        public void onAnnotatedOpen(EndpointConfig config, @PathParam("one") String one)
        {
            history.add("onOpen");
            history.add(SESSION);
            history.add(config);
            history.add(one);
        }

        @OnClose
        public void onAnnotatedClose(CloseReason closeReason,Session session, @PathParam("two") Integer two)
        {
            history.add("onClose");
            history.add(session);
            history.add(closeReason);
            history.add(two);
        }

        @OnError
        public void onAnnotatedError(@PathParam("one") String one, Session session,@PathParam("two") String two,  Throwable thr, @PathParam("three") String three)
        {
            history.add("onError");
            history.add(session);
            history.add(thr);
            history.add(one);
            history.add(two);
            history.add(three);
        }
    }

    @Test
    public void testAnnotatedParamEndpoint()
    {
        AnnotatedParamEndpoint endp = new AnnotatedParamEndpoint();
        EndpointInvoker invoker = new EndpointInvoker.Builder(AnnotatedParamEndpoint.class).build(endp, SESSION, parameters);

        Exception ex = new Exception();
        CloseReason reason = new CloseReason(CloseReason.CloseCodes.NO_STATUS_CODE,"because");

        invoker.onOpen(CONFIG);
        invoker.onError(ex);
        invoker.onClose(reason);

        assertThat(history.size(), is(14));
        assertThat(history,Matchers.contains(
                "onOpen",SESSION,CONFIG,"1",
                "onError",SESSION,ex,"1","2","3",
                "onClose",SESSION,reason,new Integer(2)));
    }




    public static class WholeHandler extends Endpoint implements MessageHandler.Whole<String>
    {
        @Override
        public void onOpen(Session session, EndpointConfig config)
        {
            history.add("onOpen");
            history.add(session);
            history.add(config);
        }


        @Override
        public void onMessage(String message)
        {
            history.add("onMessage");
            history.add(message);
        }
    }

    @Test
    public void testWholeHandler()
    {
        WholeHandler endp = new WholeHandler();
        UriTemplatePathSpec pathSpec = new UriTemplatePathSpec("/test");
        EndpointInvoker invoker = new EndpointInvoker.Builder(WholeHandler.class,pathSpec).build(endp,SESSION,parameters);

        invoker.onOpen(CONFIG);
        assertThat(invoker.onMessageHandlers().size(),is(1));
        MessageHandlerInvoker message = invoker.onMessageHandlers().get(0);

        assertTrue(String.class.equals(message.getMessageType()));
        assertTrue(Void.TYPE.equals(message.getReturnType()));

        message.invoke("Hello");

        assertThat(history.size(), is(5));
        assertThat(history,Matchers.contains(
                "onOpen",SESSION,CONFIG,
                "onMessage","Hello"));

        try
        {
            message.invoke("He", false);
            Assert.fail();
        }
        catch(Throwable th)
        {}
    }


    public static class PartialHandler extends Endpoint implements MessageHandler.Partial<ByteBuffer>
    {
        @Override
        public void onOpen(Session session, EndpointConfig config)
        {
            history.add("onOpen");
            history.add(session);
            history.add(config);
        }


        @Override
        public void onMessage(ByteBuffer message, boolean last)
        {
            history.add("onMessage");
            history.add(message);
            history.add(last);
        }
    }

    @Test
    public void testPartialHandler()
    {
        PartialHandler endp = new PartialHandler();
        UriTemplatePathSpec pathSpec = new UriTemplatePathSpec("/test");
        EndpointInvoker invoker = new EndpointInvoker.Builder(PartialHandler.class,pathSpec).build(endp,SESSION,parameters);

        invoker.onOpen(CONFIG);
        assertThat(invoker.onMessageHandlers().size(),is(1));
        MessageHandlerInvoker message = invoker.onMessageHandlers().get(0);

        assertTrue(ByteBuffer.class.equals(message.getMessageType()));
        assertTrue(Void.TYPE.equals(message.getReturnType()));

        ByteBuffer data = BufferUtil.toBuffer("Hel");
        message.invoke(data,false);

        assertThat(history.size(), is(6));
        assertThat(history,Matchers.contains(
                "onOpen",SESSION,CONFIG,
                "onMessage",data,Boolean.FALSE));

        try
        {
            message.invoke("Hello");
            Assert.fail();
        }
        catch(Throwable th)
        {}
    }


    @ServerEndpoint("/test/{one}/{two}/{three}")
    public static class AnnotatedHandler
    {
        @OnOpen
        public void onOpen(Session session, EndpointConfig config)
        {
            history.add("onOpen");
            history.add(session);
            history.add(config);
        }

        @OnMessage
        public void onMessage(ByteBuffer message, boolean last, Session session)
        {
            history.add("onMessage");
            history.add(message);
            history.add(last);
            history.add(session);
        }

        @OnMessage
        public Number onMessage(@PathParam("one") String one, String message,@PathParam("two") String two)
        {
            history.add("onMessage");
            history.add(message);
            history.add(one);
            history.add(two);
            return new Integer(Integer.parseInt(one) + Integer.parseInt(two));
        }
    }

    @Test
    public void testAnnotatedHandler()
    {
        AnnotatedHandler endp = new AnnotatedHandler();
        EndpointInvoker invoker = new EndpointInvoker.Builder(AnnotatedHandler.class).build(endp,SESSION,parameters);

        invoker.onOpen(CONFIG);
        assertThat(invoker.onMessageHandlers().size(),is(2));

        MessageHandlerInvoker whole = null;
        MessageHandlerInvoker partial = null;

        for (MessageHandlerInvoker i : invoker.onMessageHandlers())
        {
            if (i.isPartial())
                partial = i;
            else
                whole = i;
        }

        assertThat(partial,notNullValue());
        assertThat(whole,notNullValue());

        assertTrue(ByteBuffer.class.equals(partial.getMessageType()));
        assertTrue(Void.TYPE.equals(partial.getReturnType()));

        ByteBuffer data = BufferUtil.toBuffer("Hello");
        partial.invoke(data,true);

        assertThat(history.size(), is(7));
        assertThat(history,Matchers.contains(
                "onOpen",SESSION,CONFIG,
                "onMessage",data,Boolean.TRUE,SESSION));

        try
        {
            partial.invoke("Hello");
            Assert.fail();
        }
        catch(Throwable th)
        {}

        history.clear();

        assertTrue(String.class.equals(whole.getMessageType()));
        assertTrue(Number.class.equals(whole.getReturnType()));

        Object response = whole.invoke("Hello");
        assertThat(response,is(new Integer(3)));

        assertThat(history.size(), is(4));
        assertThat(history,Matchers.contains(
                "onMessage","Hello","1","2"));

        try
        {
            whole.invoke("He", false);
            Assert.fail();
        }
        catch(Throwable th)
        {}

    }


    @ServerEndpoint("/test")
    public static class AddedHandler
    {
        @OnOpen
        public void onOpen(Session session, EndpointConfig config)
        {
            history.add("onOpen");
            history.add(session);
            history.add(config);
            session.addMessageHandler(byte[].class, new MessageHandler.Partial<byte[]>()
            {
                @Override
                public void onMessage(byte[] message, boolean last)
                {
                    history.add("onMessage");
                    history.add(message);
                    history.add(last);
                }
            });

            session.addMessageHandler(String.class, new MessageHandler.Whole<String>()
            {
                @Override
                public void onMessage(String message)
                {
                    history.add("onMessage");
                    history.add(message);
                }
            });

            session.addMessageHandler(Number.class, new MessageHandler.Whole<Number>()
            {
                @Override
                public void onMessage(Number message)
                {
                    history.add("onMessage");
                    history.add(message);
                }
            });

        }
    }

    @Test
    public void testAddedHandler()
    {
        AddedHandler endp = new AddedHandler();
        EndpointInvoker invoker = new EndpointInvoker.Builder(AddedHandler.class).build(endp,SESSION,parameters);
        endpointInvoker = invoker;

        invoker.onOpen(CONFIG);
        assertThat(invoker.onMessageHandlers().size(),is(3));

        MessageHandlerInvoker whole = null;
        MessageHandlerInvoker partial = null;
        MessageHandlerInvoker other = null;

        for (MessageHandlerInvoker i : invoker.onMessageHandlers())
        {
            if (i.isPartial())
                partial = i;
            else if (String.class.equals(i.getMessageType()))
                whole = i;
            else
                other = i;
        }

        assertThat(partial,notNullValue());
        assertThat(whole,notNullValue());
        assertThat(other,notNullValue());

        assertTrue(byte[].class.equals(partial.getMessageType()));
        assertTrue(Void.TYPE.equals(partial.getReturnType()));

        byte[] data = "Hello".getBytes(StandardCharsets.UTF_8);
        partial.invoke(data,true);

        assertThat(history.size(), is(6));
        assertThat(history,Matchers.contains(
                "onOpen",SESSION,CONFIG,
                "onMessage",data,Boolean.TRUE));

        try
        {
            partial.invoke("Hello");
            Assert.fail();
        }
        catch(Throwable th)
        {}

        history.clear();

        assertTrue(String.class.equals(whole.getMessageType()));
        assertTrue(Void.TYPE.equals(whole.getReturnType()));

        whole.invoke("Hello");

        assertThat(history.size(), is(2));
        assertThat(history,Matchers.contains(
                "onMessage","Hello"));

        try
        {
            whole.invoke("He", false);
            Assert.fail();
        }
        catch(Throwable th)
        {}

        history.clear();

        assertTrue(Number.class.equals(other.getMessageType()));
        assertTrue(Void.TYPE.equals(other.getReturnType()));

        other.invoke(new Integer(42));

        assertThat(history.size(), is(2));
        assertThat(history,Matchers.contains(
                "onMessage",new Integer(42)));
    }
}
