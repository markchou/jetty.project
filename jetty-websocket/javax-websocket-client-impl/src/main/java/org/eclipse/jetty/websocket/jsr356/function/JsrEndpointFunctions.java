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

package org.eclipse.jetty.websocket.jsr356.function;

import java.io.Reader;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.Executor;
import java.util.function.Function;

import javax.websocket.ClientEndpoint;
import javax.websocket.CloseReason;
import javax.websocket.DecodeException;
import javax.websocket.Decoder;
import javax.websocket.Encoder;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.MessageHandler;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.PongMessage;
import javax.websocket.Session;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.FrameCallback;
import org.eclipse.jetty.websocket.api.InvalidWebSocketException;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.common.InvalidSignatureException;
import org.eclipse.jetty.websocket.common.function.CommonEndpointFunctions;
import org.eclipse.jetty.websocket.common.function.MethodHandleFunction;
import org.eclipse.jetty.websocket.common.invoke.InvokerUtils;
import org.eclipse.jetty.websocket.common.message.MessageSink;
import org.eclipse.jetty.websocket.common.message.PartialBinaryMessage;
import org.eclipse.jetty.websocket.common.message.PartialBinaryMessageSink;
import org.eclipse.jetty.websocket.common.message.PartialTextMessage;
import org.eclipse.jetty.websocket.common.message.PartialTextMessageSink;
import org.eclipse.jetty.websocket.common.util.ReflectUtils;
import org.eclipse.jetty.websocket.jsr356.JsrParamIdentifier;
import org.eclipse.jetty.websocket.jsr356.JsrPongMessage;
import org.eclipse.jetty.websocket.jsr356.JsrSession;
import org.eclipse.jetty.websocket.jsr356.decoders.AvailableDecoders;
import org.eclipse.jetty.websocket.jsr356.encoders.AvailableEncoders;
import org.eclipse.jetty.websocket.jsr356.messages.DecodedBinaryMessageSink;
import org.eclipse.jetty.websocket.jsr356.messages.DecodedInputStreamMessageSink;
import org.eclipse.jetty.websocket.jsr356.messages.DecodedReaderMessageSink;
import org.eclipse.jetty.websocket.jsr356.messages.DecodedTextMessageSink;

/**
 * Endpoint Functions used as interface between from the parsed websocket frames
 * and the user provided endpoint methods.
 */
public class JsrEndpointFunctions extends CommonEndpointFunctions<JsrSession>
{
    private static final Logger LOG = Log.getLogger(JsrEndpointFunctions.class);
    
    private static class MessageHandlerPongFunction implements Function<ByteBuffer, Void>
    {
        final MessageHandler messageHandler;
        public final Function<ByteBuffer, Void> function;
        
        MessageHandlerPongFunction(MessageHandler messageHandler, Function<ByteBuffer, Void> function)
        {
            this.messageHandler = messageHandler;
            this.function = function;
        }
        
        @Override
        public Void apply(ByteBuffer byteBuffer)
        {
            return function.apply(byteBuffer);
        }
    }
    
    private static class MessageHandlerSink implements MessageSink
    {
        final MessageHandler messageHandler;
        final MessageSink delegateSink;
        
        MessageHandlerSink(MessageHandler messageHandler, MessageSink messageSink)
        {
            this.messageHandler = messageHandler;
            this.delegateSink = messageSink;
        }
    
        @Override
        public void accept(Frame frame, FrameCallback callback)
        {
            this.delegateSink.accept(frame, callback);
        }
        
        @Override
        public String toString()
        {
            return String.format("MessageSink[%s]", messageHandler.getClass().getName());
        }
    }
    
    protected final AvailableEncoders encoders;
    protected final AvailableDecoders decoders;
    private final EndpointConfig endpointConfig;
    private Map<String, String> staticArgs;
    
    public JsrEndpointFunctions(Class endpointClass, WebSocketPolicy policy, Executor executor,
                                AvailableEncoders encoders, AvailableDecoders decoders,
                                Map<String, String> uriParams, EndpointConfig endpointConfig)
    {
        super(endpointClass, policy, executor);
        this.encoders = encoders;
        this.decoders = decoders;
        this.endpointConfig = endpointConfig;
        
        if (uriParams != null)
        {
            this.staticArgs = Collections.unmodifiableMap(uriParams);
        }
    }
    
    @SuppressWarnings("unused")
    public AvailableDecoders getAvailableDecoders()
    {
        return decoders;
    }
    
    /**
     * Identify the message sink the handler belongs to and remove it.
     * Block if the message sink is actively being used.
     *
     * @param handler the handler to remove from possible message sinks
     * @see javax.websocket.Session#removeMessageHandler(MessageHandler)
     * @since JSR356 v1.0
     */
    public void removeMessageHandler(MessageHandler handler)
    {
        Function<ByteBuffer, Void> pongFunction = getOnPongFunction();
        if (pongFunction instanceof MessageHandlerPongFunction)
        {
            MessageHandlerPongFunction handlerFunction = (MessageHandlerPongFunction) pongFunction;
            if (handlerFunction.messageHandler == handler)
                clearOnPongFunction();
        }
        
        MessageSink textSink = getOnTextSink();
        if (textSink instanceof MessageHandlerSink)
        {
            MessageHandlerSink handlerSink = (MessageHandlerSink) textSink;
            if (handlerSink.messageHandler == handler)
                clearOnTextSink();
        }
        
        MessageSink binarySink = getOnBinarySink();
        if (binarySink instanceof MessageHandlerSink)
        {
            MessageHandlerSink handlerSink = (MessageHandlerSink) binarySink;
            if (handlerSink.messageHandler == handler)
                clearOnBinarySink();
        }
    }
    
    /**
     * Create a message sink from the provided partial message handler.
     *
     * @param clazz the object type
     * @param handler the partial message handler
     * @param <T> the generic defined type
     * @throws IllegalStateException if unable to process message handler
     * @see javax.websocket.Session#addMessageHandler(Class, MessageHandler.Partial)
     * @since JSR356 v1.1
     */
    public <T> void setMessageHandler(Class<T> clazz, MessageHandler.Partial<T> handler) throws IllegalStateException
    {
        if (String.class.isAssignableFrom(clazz))
        {
            PartialTextMessageSink sink = new PartialTextMessageSink((partial) ->
            {
                //noinspection unchecked
                handler.onMessage((T) partial.getPayload(), partial.isFin());
                return null;
            });
            setOnText(new MessageHandlerSink(handler, sink), handler);
            return;
        }
        
        if (ByteBuffer.class.isAssignableFrom(clazz))
        {
            PartialBinaryMessageSink sink = new PartialBinaryMessageSink((partial) ->
            {
                //noinspection unchecked
                handler.onMessage((T) partial.getPayload(), partial.isFin());
                return null;
            });
            setOnBinary(new MessageHandlerSink(handler, sink), handler);
            return;
        }
        
        if (byte[].class.isAssignableFrom(clazz))
        {
            PartialBinaryMessageSink sink = new PartialBinaryMessageSink((partial) ->
            {
                handler.onMessage((T) BufferUtil.toArray(partial.getPayload()), partial.isFin());
                return null;
            });
            setOnBinary(new MessageHandlerSink(handler, sink), handler);
            return;
        }
        
        // If we reached this point, then the Partial type is unrecognized
        StringBuilder err = new StringBuilder();
        err.append("Unrecognized ").append(MessageHandler.Partial.class.getName());
        err.append(" type <");
        err.append(clazz.getName());
        err.append("> on ");
        err.append(handler.getClass().getName());
        throw new IllegalStateException(err.toString());
    }
    
    /**
     * Create a message sink from the provided whole message handler.
     *
     * @param clazz the object type
     * @param handler the whole message handler
     * @param <T> the generic defined type
     * @throws IllegalStateException if unable to process message handler
     * @see javax.websocket.Session#addMessageHandler(Class, MessageHandler.Whole)
     * @since JSR356 v1.1
     */
    public <T> void setMessageHandler(Class<T> clazz, MessageHandler.Whole<T> handler) throws IllegalStateException
    {
        try
        {
            // Is this a PongMessage?
            if (PongMessage.class.isAssignableFrom(clazz))
            {
                Function<ByteBuffer, Void> pongFunction = (payload) ->
                {
                    //noinspection unchecked
                    handler.onMessage((T) new JsrPongMessage(payload));
                    return null;
                };
                setOnPong(new MessageHandlerPongFunction(handler, pongFunction), handler);
                return;
            }
            
            // Try to determine TEXT / BINARY
            AvailableDecoders.RegisteredDecoder registeredDecoder = decoders.getRegisteredDecoderFor(clazz);
            
            if (registeredDecoder.implementsInterface(Decoder.Text.class))
            {
                Decoder.Text decoderInstance = decoders.getInstanceOf(registeredDecoder);
                DecodedTextMessageSink textSink = new DecodedTextMessageSink(
                        policy, this, decoderInstance,
                        (msg) ->
                        {
                            //noinspection unchecked
                            handler.onMessage((T) msg);
                            return null;
                        }
                );
                setOnText(new MessageHandlerSink(handler, textSink), handler);
                return;
            }
            
            if (registeredDecoder.implementsInterface(Decoder.Binary.class))
            {
                Decoder.Binary decoderInstance = decoders.getInstanceOf(registeredDecoder);
                DecodedBinaryMessageSink binarySink = new DecodedBinaryMessageSink(
                        policy, this, decoderInstance,
                        (msg) ->
                        {
                            //noinspection unchecked
                            handler.onMessage((T) msg);
                            return null;
                        }
                );
                setOnBinary(new MessageHandlerSink(handler, binarySink), handler);
                return;
            }
            
            if (registeredDecoder.implementsInterface(Decoder.TextStream.class))
            {
                Decoder.TextStream decoderInstance = decoders.getInstanceOf(registeredDecoder);
                DecodedReaderMessageSink textSink = new DecodedReaderMessageSink(
                        this,
                        getExecutor(),
                        decoderInstance,
                        (msg) ->
                        {
                            //noinspection unchecked
                            handler.onMessage((T) msg);
                            return null;
                        }
                );
                setOnText(new MessageHandlerSink(handler, textSink), handler);
                return;
            }
            
            if (registeredDecoder.implementsInterface(Decoder.BinaryStream.class))
            {
                Decoder.BinaryStream decoderInstance = decoders.getInstanceOf(registeredDecoder);
                DecodedInputStreamMessageSink binarySink = new DecodedInputStreamMessageSink(
                        this,
                        getExecutor(),
                        decoderInstance,
                        (msg) ->
                        {
                            //noinspection unchecked
                            handler.onMessage((T) msg);
                            return null;
                        }
                );
                setOnBinary(new MessageHandlerSink(handler, binarySink), handler);
                return;
            }
            
            // If we reached this point, then the Whole Message Type is unrecognized
            StringBuilder err = new StringBuilder();
            err.append("Unrecognized message type ");
            err.append(MessageHandler.Whole.class.getName());
            err.append("<").append(clazz.getName());
            err.append("> on ");
            err.append(handler.getClass().getName());
            throw new IllegalStateException(err.toString());
        }
        catch (NoSuchElementException e)
        {
            // No valid decoder for type found
            StringBuilder err = new StringBuilder();
            err.append("Not a valid ").append(MessageHandler.Whole.class.getName());
            err.append(" type <");
            err.append(clazz.getName());
            err.append("> on ");
            err.append(handler.getClass().getName());
            throw new IllegalStateException(err.toString());
        }
    }
    
    @Override
    protected void discoverEndpointFunctions(Class<?> endpointClass)
    {
        if (Endpoint.class.isAssignableFrom(endpointClass))
        {
            // Endpoint.onOpen()
            Method onOpen = ReflectUtils.findMethod(endpointClass, "onOpen", javax.websocket.Session.class, javax.websocket.EndpointConfig.class);
            setOnOpen(new MethodHandleFunction(javax.websocket.Session.class, onOpen), onOpen);

            // Endpoint.onClose()
            Method onClose = ReflectUtils.findMethod(endpointClass, "onClose", javax.websocket.Session.class, javax.websocket.CloseReason.class);
            setOnClose(new MethodHandleFunction(javax.websocket.CloseReason.class, onClose), onClose);
            // TODO: adapt CloseInfo -> CloseReason for handle
            /* eg:
             * CloseReason closeReason = new CloseReason(
             *                  CloseReason.CloseCodes.getCloseCode(close.getStatusCode()),
             *                  close.getReason());
             */

            // Endpoint.onError()
            Method onError = ReflectUtils.findMethod(endpointClass, "onError", javax.websocket.Session.class, Throwable.class);
            setOnError(new MethodHandleFunction(Throwable.class, onError), onError);

            // If using an Endpoint, there's nothing else left to map at this point.
            // Eventually, the endpoint should call .addMessageHandler() to declare
            // the various TEXT / BINARY / PONG message functions
            return;
        }
        
        discoverAnnotatedEndpointFunctions(endpointClass);
    }
    
    /**
     * Generic discovery of annotated endpoint functions.
     *
     * @param endpointClass the endpoint object
     */
    @SuppressWarnings("Duplicates")
    protected void discoverAnnotatedEndpointFunctions(Class<?> endpointClass)
    {
        // Use the JSR/Client annotation
        ClientEndpoint websocket = endpointClass.getAnnotation(ClientEndpoint.class);
        
        if (websocket != null)
        {
            encoders.registerAll(websocket.encoders());
            decoders.registerAll(websocket.decoders());
            
            // From here, the discovery of endpoint method is standard across
            // both JSR356/Client and JSR356/Server endpoints
            try
            {
                discoverJsrAnnotatedEndpointFunctions(endpointClass);
            }
            catch (DecodeException e)
            {
                throw new InvalidWebSocketException("Cannot instantiate WebSocket", e);
            }
        }
    }
    
    /**
     * JSR356 Specific discovery of Annotated Endpoint Methods
     *
     * @param endpointClass the endpoint object
     */
    protected void discoverJsrAnnotatedEndpointFunctions(Class<?> endpointClass) throws DecodeException
    {
        Method method;

        // OnOpen [0..1]
        method = ReflectUtils.findAnnotatedMethod(endpointClass, OnOpen.class);
        if (method != null)
        {
            ReflectUtils.assertIsPublicNonStatic(method);
            ReflectUtils.assertIsReturn(method, Void.TYPE); // must have no return

            InvokerUtils.Arg callingArgs[] = createCallArgs(
                    new InvokerUtils.Arg(javax.websocket.Session.class),
                    new InvokerUtils.Arg(javax.websocket.EndpointConfig.class));

            // TODO: endpointConfig is static at this point, we could make that a static arg

            // Analyze @OnOpen method declaration techniques
            MethodHandle onOpenHandle = InvokerUtils.mutatedInvoker(endpointClass, method, JsrParamIdentifier.INSTANCE, callingArgs);
            setOnOpen(new MethodHandleFunction(JsrSession.class, onOpenHandle), method);
        }
        
        // OnClose [0..1]
        method = ReflectUtils.findAnnotatedMethod(endpointClass, OnClose.class);
        if (method != null)
        {
            ReflectUtils.assertIsPublicNonStatic(method);
            ReflectUtils.assertIsReturn(method, Void.TYPE); // must have no return

            InvokerUtils.Arg callingArgs[] = createCallArgs(
                    new InvokerUtils.Arg(javax.websocket.Session.class),
                    new InvokerUtils.Arg(javax.websocket.CloseReason.class));
            
            // Analyze @OnClose method declaration techniques
            MethodHandle onCloseHandle = InvokerUtils.mutatedInvoker(endpointClass, method, JsrParamIdentifier.INSTANCE, callingArgs);
            setOnClose(new MethodHandleFunction(CloseReason.class, onCloseHandle), method);
            // TODO: adapt CloseInfo (jetty) -> CloseReason (jsr)
        }
        
        // OnError [0..1]
        method = ReflectUtils.findAnnotatedMethod(endpointClass, OnError.class);
        if (method != null)
        {
            ReflectUtils.assertIsPublicNonStatic(method);
            ReflectUtils.assertIsReturn(method, Void.TYPE); // must have no return

            InvokerUtils.Arg callingArgs[] = createCallArgs(
                    new InvokerUtils.Arg(javax.websocket.Session.class),
                    new InvokerUtils.Arg(Throwable.class));

            // Analyze @OnError method declaration techniques
            MethodHandle onErrorHandle = InvokerUtils.mutatedInvoker(endpointClass, method, JsrParamIdentifier.INSTANCE, callingArgs);
            setOnError(new MethodHandleFunction(Throwable.class, onErrorHandle), method);
        }
        
        // OnMessage [0..3] (TEXT / BINARY / PONG)
        Method onMessages[] = ReflectUtils.findAnnotatedMethods(endpointClass, OnMessage.class);
        if (onMessages != null && onMessages.length > 0)
        {
            for (Method onMsg : onMessages)
            {
                // Whole TEXT / Binary Message
                if (discoverOnMessageWholeText(onMsg)) continue;
                if (discoverOnMessageWholeBinary(onMsg)) continue;
                
                // Partial TEXT / BINARY
                if (discoverOnMessagePartialText(onMsg)) continue;
                if (discoverOnMessagePartialBinaryArray(onMsg)) continue;
                if (discoverOnMessagePartialBinaryBuffer(onMsg)) continue;
                
                // Streaming TEXT / BINARY
                if (discoverOnMessageTextStream(onMsg)) continue;
                if (discoverOnMessageBinaryStream(onMsg)) continue;
                
                // PONG
                if (discoverOnMessagePong(onMsg)) continue;
                
                // If we reached this point, then we have a @OnMessage annotated method
                // that doesn't match any known signature above.
                
                throw InvalidSignatureException.build(endpointClass, OnMessage.class, onMsg);
            }
        }
    }
    
    private boolean discoverOnMessagePong(Method onMsg) throws DecodeException
    {
        InvokerUtils.Arg callingArgs[] = createCallArgs(
                new InvokerUtils.Arg(Session.class),
                new InvokerUtils.Arg(PongMessage.class)
        );

        MethodHandle methodHandle = InvokerUtils.optionalMutatedInvoker(endpointClass, onMsg, JsrParamIdentifier.INSTANCE, callingArgs);
        if(methodHandle != null)
        {
            assertOnMessageSignature(onMsg);

            /* TODO: need return handling
             * Object ret = invoker.apply(endpoint, args);
             * getSession().getBasicRemote().sendObject(ret);
             */

            // No decoder for PongMessage
            setOnPong(new MethodHandleFunction(ByteBuffer.class, methodHandle), onMsg);

            warnOnMaxMessageSizeUse(onMsg, "PongMessage methods");
            return true;
        }
        return false;
    }
    
    private boolean discoverOnMessageBinaryStream(Method onMsg) throws DecodeException
    {
        InvokerUtils.Arg callingArgs[] = createCallArgs(
                new InvokerUtils.Arg(Session.class),
                new InvokerUtils.Arg(Decoder.BinaryStream.class)
        );

        for (AvailableDecoders.RegisteredDecoder decoder : decoders.supporting(Decoder.BinaryStream.class))
        {
            callingArgs[1] = new InvokerUtils.Arg(decoder.objectType).required();

            MethodHandle methodHandle = InvokerUtils.optionalMutatedInvoker(endpointClass, onMsg, JsrParamIdentifier.INSTANCE, callingArgs);
            if(methodHandle != null)
            {
                assertOnMessageSignature(onMsg);
                
                Decoder.BinaryStream decoderInstance = decoders.getInstanceOf(decoder);
                DecodedInputStreamMessageSink streamSink = new DecodedInputStreamMessageSink(
                        this,
                        getExecutor(),
                        decoderInstance,
                        new MethodHandleFunction(decoder.objectType, methodHandle)
                        );
                setOnBinary(streamSink, onMsg);

                warnOnMaxMessageSizeUse(onMsg, "Streaming Binary methods");
                return true;
            }
        }
        return false;
    }
    
    private boolean discoverOnMessageTextStream(Method onMsg) throws DecodeException
    {
        InvokerUtils.Arg callingArgs[] = createCallArgs(
                new InvokerUtils.Arg(Session.class),
                new InvokerUtils.Arg(Decoder.TextStream.class)
        );

        for (AvailableDecoders.RegisteredDecoder decoder : decoders.supporting(Decoder.TextStream.class))
        {
            callingArgs[1] = new InvokerUtils.Arg(decoder.objectType).required();

            MethodHandle methodHandle = InvokerUtils.optionalMutatedInvoker(endpointClass, onMsg, JsrParamIdentifier.INSTANCE, callingArgs);
            if(methodHandle != null)
            {
                assertOnMessageSignature(onMsg);
                
                Decoder.TextStream decoderInstance = decoders.getInstanceOf(decoder);
                DecodedReaderMessageSink streamSink = new DecodedReaderMessageSink(
                        this,
                        getExecutor(),
                        decoderInstance,
                        new MethodHandleFunction(Reader.class, methodHandle)
                        );
                setOnText(streamSink, onMsg);

                warnOnMaxMessageSizeUse(onMsg, "Streaming Text methods");
                return true;
            }
        }
        return false;
    }
    
    @SuppressWarnings("Duplicates")
    private boolean discoverOnMessagePartialBinaryBuffer(Method onMsg) throws DecodeException
    {
        InvokerUtils.Arg callingArgs[] = createCallArgs(
                new InvokerUtils.Arg(Session.class), // The Session
                new InvokerUtils.Arg(ByteBuffer.class).required(), // the partial BINARY message
                new InvokerUtils.Arg(boolean.class).required() // the partial/fin flag
        );

        // No decoders for Partial messages per JSR-356 (PFD1 spec)

        MethodHandle methodHandle = InvokerUtils.optionalMutatedInvoker(endpointClass, onMsg, JsrParamIdentifier.INSTANCE, callingArgs);
        if(methodHandle != null)
        {
            // Found partial binary array args
            assertOnMessageSignature(onMsg);
            
            setOnBinary(new PartialBinaryMessageSink(new MethodHandleFunction<>(PartialBinaryMessage.class, methodHandle)), onMsg);

            warnOnMaxMessageSizeUse(onMsg, "Partial Binary methods");
            return true;
        }
        return false;
    }
    
    private boolean discoverOnMessagePartialBinaryArray(Method onMsg) throws DecodeException
    {
        InvokerUtils.Arg callingArgs[] = createCallArgs(
                new InvokerUtils.Arg(Session.class), // The Session
                new InvokerUtils.Arg(byte[].class).required(), // the partial BINARY message
                new InvokerUtils.Arg(boolean.class).required() // the partial/fin flag
        );

        // No decoders for Partial messages per JSR-356 (PFD1 spec)

        MethodHandle methodHandle = InvokerUtils.optionalMutatedInvoker(endpointClass, onMsg, JsrParamIdentifier.INSTANCE, callingArgs);
        if(methodHandle != null)
        {
            // Found partial binary array args
            assertOnMessageSignature(onMsg);
            
            setOnBinary(new PartialBinaryMessageSink(new MethodHandleFunction(PartialBinaryMessage.class, methodHandle)), onMsg);

            warnOnMaxMessageSizeUse(onMsg, "Partial Binary methods");
            return true;
        }
        return false;
    }
    
    @SuppressWarnings("Duplicates")
    private boolean discoverOnMessagePartialText(Method onMsg) throws DecodeException
    {
        InvokerUtils.Arg callingArgs[] = createCallArgs(
                new InvokerUtils.Arg(Session.class), // The Session
                new InvokerUtils.Arg(String.class).required(), // the partial TEXT message
                new InvokerUtils.Arg(boolean.class).required() // the partial/fin flag
        );

        // No decoders for Partial messages per JSR-356 (PFD1 spec)

        MethodHandle methodHandle = InvokerUtils.optionalMutatedInvoker(endpointClass, onMsg, JsrParamIdentifier.INSTANCE, callingArgs);
        if(methodHandle != null)
        {
            // Found partial text args
            assertOnMessageSignature(onMsg);

            setOnText(new PartialTextMessageSink(new MethodHandleFunction(PartialTextMessage.class, methodHandle)), onMsg);

            warnOnMaxMessageSizeUse(onMsg, "Partial Text methods");
            return true;
        }
        return false;
    }
    
    private boolean discoverOnMessageWholeBinary(Method onMsg) throws DecodeException
    {
        InvokerUtils.Arg callingArgs[] = createCallArgs(
                new InvokerUtils.Arg(Session.class),
                new InvokerUtils.Arg(Decoder.Binary.class)
        );

        for (AvailableDecoders.RegisteredDecoder decoder : decoders.supporting(Decoder.Binary.class))
        {
            callingArgs[1] = new InvokerUtils.Arg(decoder.objectType).required();

            MethodHandle methodHandle = InvokerUtils.optionalMutatedInvoker(endpointClass, onMsg, JsrParamIdentifier.INSTANCE, callingArgs);
            if(methodHandle != null)
            {
                assertOnMessageSignature(onMsg);

                Decoder.Binary decoderInstance = decoders.getInstanceOf(decoder);
                DecodedBinaryMessageSink binarySink = new DecodedBinaryMessageSink(
                        policy,
                        this,
                        decoderInstance,
                        new MethodHandleFunction(ByteBuffer.class, methodHandle)
                );
                setOnBinary(binarySink, onMsg);
                OnMessage annotation = onMsg.getAnnotation(OnMessage.class);
                if (annotation.maxMessageSize() > 0)
                {
                    policy.setMaxBinaryMessageSize(annotation.maxMessageSize());
                }
                return true;
            }
        }
        return false;
    }
    
    private boolean discoverOnMessageWholeText(Method onMsg) throws DecodeException
    {
        InvokerUtils.Arg callingArgs[] = createCallArgs(
                new InvokerUtils.Arg(Session.class),
                new InvokerUtils.Arg(Decoder.Text.class)
        );

        for (AvailableDecoders.RegisteredDecoder decoder : decoders.supporting(Decoder.Text.class))
        {
            callingArgs[1] = new InvokerUtils.Arg(decoder.objectType).required();

            MethodHandle methodHandle = InvokerUtils.optionalMutatedInvoker(endpointClass, onMsg, JsrParamIdentifier.INSTANCE, callingArgs);
            if(methodHandle != null)
            {
                assertOnMessageSignature(onMsg);

                Decoder.Text decoderInstance = decoders.getInstanceOf(decoder);
                DecodedTextMessageSink textSink = new DecodedTextMessageSink(
                        policy,
                        this,
                        decoderInstance,
                        new MethodHandleFunction(String.class, methodHandle)
                );
                setOnText(textSink, onMsg);
                OnMessage annotation = onMsg.getAnnotation(OnMessage.class);
                if (annotation.maxMessageSize() > 0)
                {
                    policy.setMaxTextMessageSize(annotation.maxMessageSize());
                }
                return true;
            }
        }
        return false;
    }

    private void warnOnMaxMessageSizeUse(Method method, String scope)
    {
        // Warn when maxMessageSize has been set for partial support
        OnMessage annotation = method.getAnnotation(OnMessage.class);
        if (annotation.maxMessageSize() > 0)
        {
            StringBuilder err = new StringBuilder();
            err.append("@OnMessage.maxMessageSize=").append(annotation.maxMessageSize());
            err.append(" ignored for ").append(scope).append(": ");
            ReflectUtils.append(err, method);
            LOG.warn(err.toString());
        }
    }

    private void assertOnMessageSignature(Method method)
    {
        // Test modifiers
        int mods = method.getModifiers();
        if (!Modifier.isPublic(mods))
        {
            StringBuilder err = new StringBuilder();
            err.append("@OnMessage method must be public: ");
            ReflectUtils.append(err, endpointClass, method);
            throw new InvalidSignatureException(err.toString());
        }
        
        if (Modifier.isStatic(mods))
        {
            StringBuilder err = new StringBuilder();
            err.append("@OnMessage method must NOT be static: ");
            ReflectUtils.append(err, endpointClass, method);
            throw new InvalidSignatureException(err.toString());
        }
        
        // Test return type
        Class<?> returnType = method.getReturnType();
        if ((returnType == Void.TYPE) || (returnType == Void.class))
        {
            // Void is 100% valid, always
            return;
        }
        
        // Test specific return type to ensure we have a compatible encoder for it
        Class<? extends Encoder> encoderClass = encoders.getEncoderFor(returnType);
        if (encoderClass == null)
        {
            StringBuilder err = new StringBuilder();
            err.append("@OnMessage return type invalid (no valid encoder found): ");
            ReflectUtils.append(err, endpointClass, method);
            throw new InvalidSignatureException(err.toString());
        }
    }

//    TODO
//    private Object[] newFunctionArgs(Arg[] callArgs, Method destMethod, int argMapping[]) throws DecodeException
//    {
//        Object[] potentialArgs = new Object[callArgs.length];
//
//        if ((staticArgs == null) || (staticArgs.isEmpty()))
//        {
//            // No static args, then potentialArgs is empty too
//            return potentialArgs;
//        }
//
//        Class<?>[] paramTypes = destMethod.getParameterTypes();
//        int paramTypesLen = paramTypes.length;
//
//        for (int i = 0; i < paramTypesLen; i++)
//        {
//            Class destType = paramTypes[i];
//            Arg callArg = callArgs[argMapping[i]];
//            String staticRawValue = staticArgs.get(callArg.getTag());
//            TODO: potentialArgs[argMapping[i]] = AvailableDecoders.decodePrimitive(staticRawValue, destType);
//
//        }
//        return potentialArgs;
//    }

    /**
     * Create the calling args array, complete with entries for all of the identified static args
     * that can arrive from named URI-Template variables.
     * <p>
     *     Each calling arg from a URI-Template variable is called via the String type.
     *     The conversion to the method parameter type is done in a different step.
     * </p>
     *
     * @param args the known calling args that exist, regardless if URI-Template variables exist or not.
     * @return the list of Calling Args
     */
    private InvokerUtils.Arg[] createCallArgs(InvokerUtils.Arg... args)
    {
        /* TODO: this could be smarter by looking at the parameter types on the destination method too
         * Optionally even performing a CallingArg -> Decoder -> ParameterType conversion?
         */

        /* It might be tempting to have MethodHandle have static callingArgs for the PathParam entries at this point.
         * However, the values for these static PathParam entries are not known until upgrade time,
         * as that is when the values are passed into the environment via the submitted request URI.
         */

        int argCount = args.length;
        if (this.staticArgs != null)
            argCount += this.staticArgs.size();

        InvokerUtils.Arg callArgs[] = new InvokerUtils.Arg[argCount];
        int idx = 0;
        for (InvokerUtils.Arg arg : args)
        {
            callArgs[idx++] = arg;
        }

        if (this.staticArgs != null)
        {
            for (Map.Entry<String, String> entry : staticArgs.entrySet())
            {
                callArgs[idx++] = new InvokerUtils.Arg(entry.getValue().getClass(), entry.getKey());
            }
        }
        return callArgs;
    }
}
