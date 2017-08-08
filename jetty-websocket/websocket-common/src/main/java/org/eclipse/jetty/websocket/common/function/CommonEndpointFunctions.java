//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.common.function;

import java.io.InputStream;
import java.io.Reader;
import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Function;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.BatchMode;
import org.eclipse.jetty.websocket.api.FrameCallback;
import org.eclipse.jetty.websocket.api.InvalidWebSocketException;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketConnectionListener;
import org.eclipse.jetty.websocket.api.WebSocketFrameListener;
import org.eclipse.jetty.websocket.api.WebSocketListener;
import org.eclipse.jetty.websocket.api.WebSocketPartialListener;
import org.eclipse.jetty.websocket.api.WebSocketPingPongListener;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketFrame;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.EndpointFunctions;
import org.eclipse.jetty.websocket.common.InvalidSignatureException;
import org.eclipse.jetty.websocket.common.invoke.InvokerUtils;
import org.eclipse.jetty.websocket.common.message.ByteArrayMessageSink;
import org.eclipse.jetty.websocket.common.message.ByteBufferMessageSink;
import org.eclipse.jetty.websocket.common.message.InputStreamMessageSink;
import org.eclipse.jetty.websocket.common.message.MessageSink;
import org.eclipse.jetty.websocket.common.message.PartialBinaryMessage;
import org.eclipse.jetty.websocket.common.message.PartialBinaryMessageSink;
import org.eclipse.jetty.websocket.common.message.PartialTextMessage;
import org.eclipse.jetty.websocket.common.message.PartialTextMessageSink;
import org.eclipse.jetty.websocket.common.message.ReaderMessageSink;
import org.eclipse.jetty.websocket.common.message.StringMessageSink;
import org.eclipse.jetty.websocket.common.util.ReflectUtils;

/**
 * The Common Implementation of EndpointFunctions
 *
 * @param <T> the Session object
 */
public class CommonEndpointFunctions<T extends Session> extends AbstractLifeCycle implements EndpointFunctions<T>
{
    private static final Logger LOG = Log.getLogger(CommonEndpointFunctions.class);

    protected final Class<?> endpointClass;
    protected final WebSocketPolicy policy;
    protected final Executor executor;

    protected Logger endpointLog;
    private T session;
    private Function<T, Void> onOpenFunction;
    private Function<CloseInfo, Void> onCloseFunction;
    private Function<Throwable, Void> onErrorFunction;
    private Function<Frame, Void> onFrameFunction;
    private Function<ByteBuffer, Void> onPingFunction;
    private Function<ByteBuffer, Void> onPongFunction;

    private MessageSink onTextSink;
    private MessageSink onBinarySink;
    private MessageSink activeMessageSink;

    private BatchMode batchMode;

    public CommonEndpointFunctions(Class endpointClass, WebSocketPolicy policy, Executor executor)
    {
        Objects.requireNonNull(endpointClass, "Endpoint Class cannot be null");
        Objects.requireNonNull(policy, "WebSocketPolicy cannot be null");
        Objects.requireNonNull(executor, "Executor cannot be null");
        this.endpointClass = endpointClass;
        this.policy = policy;
        this.executor = executor;
    }

    @Override
    protected void doStart() throws Exception
    {
        discoverEndpointFunctions(this.endpointClass);
        super.doStart();
    }

    protected void discoverEndpointFunctions(Class<?> endpointClass)
    {
        boolean supportAnnotations = true;

        // Connection Listener
        if (WebSocketConnectionListener.class.isAssignableFrom(endpointClass))
        {
            Method onOpen = ReflectUtils.findMethod(endpointClass, "onWebSocketConnect", Session.class);
            setOnOpen(new MethodHandleFunction(Session.class, onOpen), onOpen);
            Method onClose = ReflectUtils.findMethod(endpointClass, "onWebSocketClose", int.class, String.class);
            // TODO: need CloseInfo -> int/String layer for MethodHandle
            setOnClose(new MethodHandleFunction(CloseInfo.class, onClose), onClose);
            Method onError = ReflectUtils.findMethod(endpointClass, "onWebSocketError", Throwable.class);
            setOnError(new MethodHandleFunction(Throwable.class, onError), onError);
            supportAnnotations = false;
        }

        // Simple Data Listener
        if (WebSocketListener.class.isAssignableFrom(endpointClass))
        {
            Method onText = ReflectUtils.findMethod(endpointClass, "onWebSocketText", String.class);
            setOnText(new StringMessageSink(policy, new MethodHandleFunction<>(String.class, onText)), onText);
            Method onBinary = ReflectUtils.findMethod(endpointClass, "onWebSocketBinary", byte[].class, int.class, int.class);
            setOnBinary(new ByteArrayMessageSink(policy, new MethodHandleFunction<>(byte[].class, onBinary)), onBinary);
            supportAnnotations = false;
        }

        // Ping/Pong Listener
        if (WebSocketPingPongListener.class.isAssignableFrom(endpointClass))
        {
            Method onPong = ReflectUtils.findMethod(endpointClass, "onWebSocketPong", ByteBuffer.class);
            setOnPong(new MethodHandleFunction(ByteBuffer.class, onPong), onPong);
            Method onPing = ReflectUtils.findMethod(endpointClass, "onWebSocketPing", ByteBuffer.class);
            setOnPing(new MethodHandleFunction(ByteBuffer.class, onPing), onPing);
            supportAnnotations = false;
        }

        // Partial Data / Message Listener
        if (WebSocketPartialListener.class.isAssignableFrom(endpointClass))
        {
            Method onTextPartial = ReflectUtils.findMethod(endpointClass, "onWebSocketPartialText", String.class, boolean.class);
            // TODO: need PartialTextMessage layer for MethodHandle
            setOnText(new PartialTextMessageSink(new MethodHandleFunction(PartialTextMessage.class, onTextPartial)), onTextPartial);
            Method onBinaryPartial = ReflectUtils.findMethod(endpointClass, "onWebSocketPartialBinary", ByteBuffer.class, boolean.class);
            // TODO: need PartialBinaryMessage layer for MethodHandle
            setOnBinary(new PartialBinaryMessageSink(new MethodHandleFunction(PartialBinaryMessage.class, onBinaryPartial)), onBinaryPartial);
            supportAnnotations = false;
        }

        // Frame Listener
        if (WebSocketFrameListener.class.isAssignableFrom(endpointClass))
        {
            Method onFrame = ReflectUtils.findMethod(endpointClass, "onWebSocketFrame", Frame.class);
            // TODO: need ReadOnlyDelegatedFrame layer for MethodHandle
            setOnFrame(new MethodHandleFunction(Frame.class, onFrame), onFrame);
            supportAnnotations = false;
        }

        if (supportAnnotations)
            discoverAnnotatedEndpointFunctions(endpointClass);
    }

    protected void discoverAnnotatedEndpointFunctions(Class<?> endpointClass)
    {
        // Test for annotated websocket endpoint

        WebSocket websocket = endpointClass.getAnnotation(WebSocket.class);
        if (websocket != null)
        {
            policy.setInputBufferSize(websocket.inputBufferSize());
            policy.setMaxBinaryMessageSize(websocket.maxBinaryMessageSize());
            policy.setMaxTextMessageSize(websocket.maxTextMessageSize());
            policy.setIdleTimeout(websocket.maxIdleTime());

            this.batchMode = websocket.batchMode();

            Method onmethod;

            // OnWebSocketConnect [0..1]
            onmethod = ReflectUtils.findAnnotatedMethod(endpointClass, OnWebSocketConnect.class);
            if (onmethod != null)
            {
                final InvokerUtils.Arg SESSION = new InvokerUtils.Arg(Session.class).required();
                MethodHandle methodHandle = InvokerUtils.mutatedInvoker(endpointClass, onmethod, SESSION);
                setOnOpen(new MethodHandleFunction(Session.class, methodHandle), onmethod);
            }

            // OnWebSocketClose [0..1]
            onmethod = ReflectUtils.findAnnotatedMethod(endpointClass, OnWebSocketClose.class);
            if (onmethod != null)
            {
                final InvokerUtils.Arg SESSION = new InvokerUtils.Arg(Session.class);
                final InvokerUtils.Arg STATUS_CODE = new InvokerUtils.Arg(int.class);
                final InvokerUtils.Arg REASON = new InvokerUtils.Arg(String.class);
                MethodHandle methodHandle = InvokerUtils.mutatedInvoker(endpointClass, onmethod, SESSION, STATUS_CODE, REASON);
                // TODO: need mutation of args ...
                // Session + CloseInfo ->
                // setOnClose((closeInfo) ->{
                // args[0] = getSession();
                // args[1] = closeInfo.getStatusCode();
                // args[2] = closeInfo.getReason();
                // invoker.apply(endpoint, args);
                setOnClose(new MethodHandleFunction(CloseInfo.class, methodHandle), onmethod);
            }

            // OnWebSocketError [0..1]
            onmethod = ReflectUtils.findAnnotatedMethod(endpointClass, OnWebSocketError.class);
            if (onmethod != null)
            {
                final InvokerUtils.Arg SESSION = new InvokerUtils.Arg(Session.class);
                final InvokerUtils.Arg CAUSE = new InvokerUtils.Arg(Throwable.class).required();
                MethodHandle methodHandle = InvokerUtils.mutatedInvoker(endpointClass, onmethod, SESSION, CAUSE);
                setOnError(new MethodHandleFunction<>(Throwable.class, methodHandle), onmethod);
            }

            // OnWebSocketFrame [0..1]
            onmethod = ReflectUtils.findAnnotatedMethod(endpointClass, OnWebSocketFrame.class);
            if (onmethod != null)
            {
                final InvokerUtils.Arg SESSION = new InvokerUtils.Arg(Session.class);
                final InvokerUtils.Arg FRAME = new InvokerUtils.Arg(Frame.class).required();
                MethodHandle methodHandle = InvokerUtils.mutatedInvoker(endpointClass, onmethod, SESSION, FRAME);
                setOnFrame(new MethodHandleFunction(Frame.class, methodHandle), onmethod);
            }
            // OnWebSocketMessage [0..2]
            Method onMessages[] = ReflectUtils.findAnnotatedMethods(endpointClass, OnWebSocketMessage.class);
            if (onMessages != null && onMessages.length > 0)
            {
                // The different kind of @OnWebSocketMessage method parameter signatures expected

                InvokerUtils.Arg textCallingArgs[] = new InvokerUtils.Arg[]{
                        new InvokerUtils.Arg(Session.class),
                        new InvokerUtils.Arg(String.class).required()
                };

                InvokerUtils.Arg binaryBufferCallingArgs[] = new InvokerUtils.Arg[]{
                        new InvokerUtils.Arg(Session.class),
                        new InvokerUtils.Arg(ByteBuffer.class).required()
                };

                InvokerUtils.Arg binaryArrayCallingArgs[] = new InvokerUtils.Arg[]{
                        new InvokerUtils.Arg(Session.class),
                        new InvokerUtils.Arg(byte[].class).required(),
                        new InvokerUtils.Arg(int.class), // offset
                        new InvokerUtils.Arg(int.class) // length
                };

                InvokerUtils.Arg inputStreamCallingArgs[] = new InvokerUtils.Arg[]{
                        new InvokerUtils.Arg(Session.class),
                        new InvokerUtils.Arg(InputStream.class).required()
                };

                InvokerUtils.Arg readerCallingArgs[] = new InvokerUtils.Arg[]{
                        new InvokerUtils.Arg(Session.class),
                        new InvokerUtils.Arg(Reader.class).required()
                };

                onmessageloop: for (Method onMsg : onMessages)
                {
                    MethodHandle methodHandle = InvokerUtils.optionalMutatedInvoker(endpointClass, onMsg, InvokerUtils.PARAM_IDENTITY, textCallingArgs);
                    if (methodHandle != null)
                    {
                        // Normal Text Message
                        assertSignatureValid(onMsg, OnWebSocketMessage.class);
                        setOnText(new StringMessageSink(policy, new MethodHandleFunction(String.class, methodHandle)), onMsg);
                        break onmessageloop;
                    }

                    methodHandle = InvokerUtils.optionalMutatedInvoker(endpointClass, onMsg, InvokerUtils.PARAM_IDENTITY, binaryBufferCallingArgs);
                    if (methodHandle != null)
                    {
                        // ByteBuffer Binary Message
                        assertSignatureValid(onMsg, OnWebSocketMessage.class);
                        setOnBinary(new ByteBufferMessageSink(policy, new MethodHandleFunction(ByteBuffer.class, methodHandle)), onMsg);
                        break onmessageloop;
                    }

                    methodHandle = InvokerUtils.optionalMutatedInvoker(endpointClass, onMsg, InvokerUtils.PARAM_IDENTITY, binaryArrayCallingArgs);
                    if (methodHandle != null)
                    {
                        // byte[] Binary Message
                        assertSignatureValid(onMsg, OnWebSocketMessage.class);
                        setOnBinary(new ByteBufferMessageSink(policy, new MethodHandleFunction(byte[].class, methodHandle)), onMsg);
                        // TODO: split ByteBuffer into byte[], offset, length ?
                        break onmessageloop;
                    }

                    methodHandle = InvokerUtils.optionalMutatedInvoker(endpointClass, onMsg, InvokerUtils.PARAM_IDENTITY, inputStreamCallingArgs);
                    if (methodHandle != null)
                    {
                        // InputStream Binary Message
                        assertSignatureValid(onMsg, OnWebSocketMessage.class);
                        setOnBinary(new InputStreamMessageSink(executor, new MethodHandleFunction(InputStream.class, methodHandle)), onMsg);
                        break onmessageloop;
                    }

                    methodHandle = InvokerUtils.optionalMutatedInvoker(endpointClass, onMsg, InvokerUtils.PARAM_IDENTITY, readerCallingArgs);
                    if (methodHandle != null)
                    {
                        // Reader Text Message
                        assertSignatureValid(onMsg, OnWebSocketMessage.class);
                        setOnBinary(new ReaderMessageSink(executor, new MethodHandleFunction(Reader.class, methodHandle)), onMsg);
                        break onmessageloop;
                    }
                    else
                    {
                        // Not a valid @OnWebSocketMessage declaration signature
                        throw InvalidSignatureException.build(endpointClass, OnWebSocketMessage.class, onMsg);
                    }
                }
            }
        }
    }

    private void assertSignatureValid(Method method, Class<? extends Annotation> annotationClass)
    {
        // Test modifiers
        int mods = method.getModifiers();
        if (!Modifier.isPublic(mods))
        {
            StringBuilder err = new StringBuilder();
            err.append("@").append(annotationClass.getSimpleName());
            err.append(" method must be public: ");
            ReflectUtils.append(err, endpointClass, method);
            throw new InvalidSignatureException(err.toString());
        }

        if (Modifier.isStatic(mods))
        {
            StringBuilder err = new StringBuilder();
            err.append("@").append(annotationClass.getSimpleName());
            err.append(" method must not be static: ");
            ReflectUtils.append(err, endpointClass, method);
            throw new InvalidSignatureException(err.toString());
        }

        // Test return type
        Class<?> returnType = method.getReturnType();
        if ((returnType == Void.TYPE) || (returnType == Void.class))
        {
            // For the Jetty Native WebSocket API, void is only supported case
            return;
        }

        StringBuilder err = new StringBuilder();
        err.append("@").append(annotationClass.getSimpleName());
        err.append(" return must be void: ");
        ReflectUtils.append(err, endpointClass, method);
        throw new InvalidSignatureException(err.toString());
    }

    protected void clearOnPongFunction()
    {
        onPongFunction = null;
    }

    protected void clearOnTextSink()
    {
        onTextSink = null;
    }

    protected void clearOnBinarySink()
    {
        onBinarySink = null;
    }

    public BatchMode getBatchMode()
    {
        return batchMode;
    }

    public Executor getExecutor()
    {
        return executor;
    }

    public Logger getLog()
    {
        if (endpointLog == null)
        {
            endpointLog = Log.getLogger(endpointClass);
        }

        return endpointLog;
    }

    public T getSession()
    {
        return session;
    }

    protected MessageSink getOnTextSink()
    {
        return onTextSink;
    }

    protected MessageSink getOnBinarySink()
    {
        return onBinarySink;
    }

    protected Function<ByteBuffer, Void> getOnPongFunction()
    {
        return onPongFunction;
    }

    protected void setOnOpen(Function<T, Void> function, Object origin)
    {
        assertNotSet(this.onOpenFunction, "Open Handler", origin);
        this.onOpenFunction = function;
        if (LOG.isDebugEnabled())
        {
            LOG.debug("Assigned onOpen to " + describeOrigin(origin));
        }
    }

    protected void setOnClose(Function<CloseInfo, Void> function, Object origin)
    {
        assertNotSet(this.onCloseFunction, "Close Handler", origin);
        this.onCloseFunction = function;
        if (LOG.isDebugEnabled())
        {
            LOG.debug("Assigned onClose to " + describeOrigin(origin));
        }
    }

    protected void setOnError(Function<Throwable, Void> function, Object origin)
    {
        assertNotSet(this.onErrorFunction, "Error Handler", origin);
        this.onErrorFunction = function;
        if (LOG.isDebugEnabled())
        {
            LOG.debug("Assigned onError to " + describeOrigin(origin));
        }
    }

    protected void setOnText(MessageSink messageSink, Object origin)
    {
        assertNotSet(this.onTextSink, "TEXT Handler", origin);
        this.onTextSink = messageSink;
        if (LOG.isDebugEnabled())
        {
            LOG.debug("Assigned onText to " + describeOrigin(origin));
        }
    }

    protected void setOnBinary(MessageSink messageSink, Object origin)
    {
        assertNotSet(this.onBinarySink, "BINARY Handler", origin);
        this.onBinarySink = messageSink;
        if (LOG.isDebugEnabled())
        {
            LOG.debug("Assigned onBinary to " + describeOrigin(origin));
        }
    }

    protected void setOnFrame(Function<Frame, Void> function, Object origin)
    {
        assertNotSet(this.onFrameFunction, "Frame Handler", origin);
        this.onFrameFunction = function;
        if (LOG.isDebugEnabled())
        {
            LOG.debug("Assigned onFrame to " + describeOrigin(origin));
        }
    }

    protected void setOnPing(Function<ByteBuffer, Void> function, Object origin)
    {
        assertNotSet(this.onPingFunction, "Ping Handler", origin);
        this.onPingFunction = function;
        if (LOG.isDebugEnabled())
        {
            LOG.debug("Assigned onPing to " + describeOrigin(origin));
        }
    }

    protected void setOnPong(Function<ByteBuffer, Void> function, Object origin)
    {
        assertNotSet(this.onPongFunction, "Pong Handler", origin);
        this.onPongFunction = function;
        if (LOG.isDebugEnabled())
        {
            LOG.debug("Assigned onPong to " + describeOrigin(origin));
        }
    }

    public boolean hasBinarySink()
    {
        return this.onBinarySink != null;
    }

    public boolean hasTextSink()
    {
        return this.onTextSink != null;
    }

    public boolean hasOnOpen()
    {
        return this.onOpenFunction != null;
    }

    public boolean hasOnClose()
    {
        return this.onCloseFunction != null;
    }

    public boolean hasOnError()
    {
        return this.onErrorFunction != null;
    }

    public boolean hasOnFrame()
    {
        return this.onFrameFunction != null;
    }

    private String describeOrigin(Object obj)
    {
        if (obj == null)
        {
            return "<undefined>";
        }

        return obj.toString();
    }

    private void assertNotSet(Object val, String role, Object origin)
    {
        if (val == null)
            return;

        StringBuilder err = new StringBuilder();
        err.append("Cannot replace previously assigned [");
        err.append(role);
        err.append("] at ").append(describeOrigin(val));
        err.append(" with ");
        err.append(describeOrigin(origin));

        throw new InvalidWebSocketException(err.toString());
    }

    @Override
    public void onOpen(T session)
    {
        assertIsStarted();

        // Always set session in endpoint functions
        this.session = session;

        // Call (optional) on open method
        // TODO: catch end user throwables
        if (onOpenFunction != null)
            onOpenFunction.apply(this.session);
    }

    @Override
    public void onClose(CloseInfo close)
    {
        assertIsStarted();

        // TODO: catch end user throwables
        if (onCloseFunction != null)
            onCloseFunction.apply(close);
    }

    @Override
    public void onFrame(Frame frame)
    {
        assertIsStarted();

        // TODO: catch end user throwables
        if (onFrameFunction != null)
            onFrameFunction.apply(frame);
    }

    @Override
    public void onError(Throwable cause)
    {
        assertIsStarted();

        if (onErrorFunction != null)
            onErrorFunction.apply(cause);
        else
            LOG.debug(cause);
    }

    @Override
    public void onText(Frame frame, FrameCallback callback)
    {
        assertIsStarted();

        if (activeMessageSink == null)
            activeMessageSink = onTextSink;

        acceptMessage(frame, callback);
    }

    @Override
    public void onBinary(Frame frame, FrameCallback callback)
    {
        assertIsStarted();

        if (activeMessageSink == null)
            activeMessageSink = onBinarySink;

        acceptMessage(frame, callback);
    }

    @Override
    public void onContinuation(Frame frame, FrameCallback callback)
    {
        // TODO: catch end user throwables
        acceptMessage(frame, callback);
    }

    private void acceptMessage(Frame frame, FrameCallback callback)
    {
        // No message sink is active
        if (activeMessageSink == null)
            return;

        // Accept the payload into the message sink
        // TODO: catch end user throwables
        activeMessageSink.accept(frame, callback);
        if (frame.isFin())
            activeMessageSink = null;
    }

    @Override
    public void onPing(ByteBuffer payload)
    {
        assertIsStarted();

        // TODO: catch end user throwables
        if (onPingFunction != null)
        {
            if (payload == null)
                payload = BufferUtil.EMPTY_BUFFER;

            onPingFunction.apply(payload);
        }
    }

    @Override
    public void onPong(ByteBuffer payload)
    {
        assertIsStarted();

        // TODO: catch end user throwables
        if (onPongFunction != null)
        {
            if (payload == null)
                payload = BufferUtil.EMPTY_BUFFER;

            onPongFunction.apply(payload);
        }
    }

    private void assertIsStarted()
    {
        if (!isStarted())
            throw new IllegalStateException(this.getClass().getName() + " not started");
    }

    @Override
    public String toString()
    {
        return String.format("%s[%s]", this.getClass().getSimpleName(), getState());
    }
}
