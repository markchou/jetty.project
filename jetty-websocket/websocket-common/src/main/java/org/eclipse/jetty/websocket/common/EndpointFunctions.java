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

package org.eclipse.jetty.websocket.common;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.FrameCallback;
import org.eclipse.jetty.websocket.api.extensions.Frame;

/**
 * The interface a WebSocket Connection and Session has to the User provided Endpoint.
 *
 * @param <T> the Session object
 */
public interface EndpointFunctions<T> extends LifeCycle
{
    /**
     * Obtain the Endpoint specific logger
     *
     * @return the logger specific to the endpoint
     */
    Logger getLog();

    /**
     * Get the Session used for this Endpoint instance.
     *
     * @return the Session for this Endpoint instance
     */
    T getSession();

    /**
     * Open the Endpoint
     *
     * @param session the session to open the endpoint with
     */
    void onOpen(T session);

    /**
     * Close the Endpoint
     *
     * @param close the close information
     */
    void onClose(CloseInfo close);

    /**
     * Handle an incoming WebSocket Frame
     *
     * @param frame the frame received
     */
    void onFrame(Frame frame);

    /**
     * Notify the Endpoint of an error that is closing the session
     *
     * @param cause the cause of the error
     */
    void onError(Throwable cause);

    /**
     * Handle an incoming Text frame
     *
     * @param frame the text frame
     * @param callback the callback for the frame (to indicate that the endpoint has handled it, or not)
     */
    void onText(Frame frame, FrameCallback callback);

    /**
     * Handle an incoming Binary frame
     *
     * @param frame the binary frame
     * @param callback the callback for the frame (to indicate that the endpoint has handled it, or not)
     */
    void onBinary(Frame frame, FrameCallback callback);

    /**
     * Handle an incoming Continuation frame
     *
     * @param frame the continuation frame
     * @param callback the callback for the frame (to indicate that the endpoint has handled it, or not)
     */
    void onContinuation(Frame frame, FrameCallback callback);

    /**
     * Notify of an incoming Ping control frame
     *
     * @param payload the Ping frame payload
     */
    void onPing(ByteBuffer payload);

    /**
     * Notify of an incoming Pong control frame
     *
     * @param payload the Pong frame payload
     */
    void onPong(ByteBuffer payload);
}
