/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Adapted for SpongeTube M2-F0 from Android CTS PacketReflector. The adaptation
 * intentionally keeps only the IPv4/TCP path needed by the feasibility proof
 * and adds bounded traffic counters. It remains androidTest-only.
 */
package io.github.definitelystable.spongetube.core.engine.route;

import android.system.ErrnoException;
import android.system.Os;
import java.io.FileDescriptor;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

final class RouteVpnPacketReflector extends Thread {
    private static final int IPV4_MIN_HEADER_LENGTH = 20;
    private static final int IPV4_ADDR_OFFSET = 12;
    private static final int IPV4_ADDR_LENGTH = 4;
    private static final int IPV4_PROTO_OFFSET = 9;
    private static final int TCP_MIN_HEADER_LENGTH = 20;
    private static final int IPPROTO_TCP = 6;

    private final FileDescriptor fd;
    private final byte[] buffer;
    private final AtomicLong reflectedPackets = new AtomicLong();
    private final AtomicLong reflectedBytes = new AtomicLong();

    RouteVpnPacketReflector(FileDescriptor fd, int mtu) {
        super("M2-F0-VpnPacketReflector");
        this.fd = fd;
        this.buffer = new byte[mtu];
    }

    long reflectedPackets() {
        return reflectedPackets.get();
    }

    long reflectedBytes() {
        return reflectedBytes.get();
    }

    private static void swapBytes(byte[] value, int first, int second, int length) {
        for (int i = 0; i < length; i++) {
            byte temporary = value[first + i];
            value[first + i] = value[second + i];
            value[second + i] = temporary;
        }
    }

    private void processPacket(int length) throws ErrnoException, IOException {
        if (length < IPV4_MIN_HEADER_LENGTH || (buffer[0] >>> 4) != 4) {
            return;
        }
        int headerLength = (buffer[0] & 0x0f) * 4;
        if (headerLength < IPV4_MIN_HEADER_LENGTH
                || length < headerLength + TCP_MIN_HEADER_LENGTH
                || (buffer[IPV4_PROTO_OFFSET] & 0xff) != IPPROTO_TCP) {
            return;
        }

        /*
         * Swapping only IPv4 source/destination preserves the TCP pseudo-header
         * checksum sum. Ports intentionally remain unchanged. A connection to
         * the synthetic peer therefore terminates at a local ServerSocket with
         * the same destination port after crossing the TUN in both directions.
         */
        swapBytes(
                buffer,
                IPV4_ADDR_OFFSET,
                IPV4_ADDR_OFFSET + IPV4_ADDR_LENGTH,
                IPV4_ADDR_LENGTH);
        Os.write(fd, buffer, 0, length);
        reflectedPackets.incrementAndGet();
        reflectedBytes.addAndGet(length);
    }

    @Override
    public void run() {
        while (!isInterrupted() && fd.valid()) {
            try {
                int length = Os.read(fd, buffer, 0, buffer.length);
                if (length <= 0) {
                    return;
                }
                processPacket(length);
            } catch (ErrnoException | IOException error) {
                return;
            }
        }
    }
}
