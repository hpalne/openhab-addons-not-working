/**
 * Copyright (c) 2010-2021 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.danfossairunit.internal;

import static org.openhab.binding.danfossairunit.internal.Commands.EMPTY;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.util.Arrays;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link DanfossAirUnitCommunicationController} class does the actual network communication with the air unit.
 *
 * @author Robert Bach - initial contribution
 */

@NonNullByDefault
public class DanfossAirUnitCommunicationController implements CommunicationController {

    private final Logger logger = LoggerFactory.getLogger(DanfossAirUnitCommunicationController.class);

    private final InetAddress inetAddr;
    private final int port;
    private boolean connected = false;
    private @Nullable Socket socket;
    private @Nullable OutputStream outputStream;
    private @Nullable InputStream inputStream;

    public DanfossAirUnitCommunicationController(InetAddress inetAddr, int port) {
        this.inetAddr = inetAddr;
        this.port = port;
    }

    public synchronized void connect() throws IOException {
        if (connected) {
            return;
        }
        Socket localSocket = new Socket(inetAddr, port);
        this.outputStream = localSocket.getOutputStream();
        this.inputStream = localSocket.getInputStream();
        this.socket = localSocket;
        connected = true;
    }

    public synchronized void disconnect() {
        if (!connected) {
            return;
        }
        try {
            Socket localSocket = this.socket;
            if (localSocket != null) {
                localSocket.close();
            }
        } catch (IOException ioe) {
            logger.debug("Connection to air unit could not be closed gracefully. {}", ioe.getMessage());
        } finally {
            this.socket = null;
            this.inputStream = null;
            this.outputStream = null;
        }
        connected = false;
    }

    public byte[] sendRobustRequest(byte[] operation, byte[] register) throws IOException {
        return sendRobustRequest(operation, register, EMPTY);
    }

    public synchronized byte[] sendRobustRequest(byte[] operation, byte[] register, byte[] value) throws IOException {
        connect();
        byte[] request = new byte[4 + value.length];
        System.arraycopy(operation, 0, request, 0, 2);
        System.arraycopy(register, 0, request, 2, 2);
        System.arraycopy(value, 0, request, 4, value.length);
        try {
            return sendRequestInternal(request);
        } catch (IOException ioe) {
            // retry once if there was connection problem
            disconnect();
            connect();
            return sendRequestInternal(request);
        }
    }

    private synchronized byte[] sendRequestInternal(byte[] request) throws IOException {
        OutputStream localOutputStream = this.outputStream;

        if (localOutputStream == null) {
            throw new IOException(
                    String.format("Output stream is null while sending request: %s", Arrays.toString(request)));
        }
        localOutputStream.write(request);
        localOutputStream.flush();

        byte[] result = new byte[63];
        InputStream localInputStream = this.inputStream;
        if (localInputStream == null) {
            throw new IOException(
                    String.format("Input stream is null while sending request: %s", Arrays.toString(request)));
        }
        // noinspection ResultOfMethodCallIgnored
        localInputStream.read(result, 0, 63);

        return result;
    }
}
