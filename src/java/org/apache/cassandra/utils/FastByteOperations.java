/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.utils;

import java.lang.reflect.Field;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.AccessController;
import java.security.PrivilegedAction;

import com.google.common.primitives.*;

import net.nicoulaj.compilecommand.annotations.Inline;
import sun.misc.Unsafe;

/**
 * Utility code to do optimized byte-array comparison.
 * This is borrowed and slightly modified from Guava's {@link UnsignedBytes}
 * class to be able to compare arrays that start at non-zero offsets.
 */
public class FastByteOperations
{

    /**
     * Lexicographically compare two byte arrays.
     */
    public static int compareUnsigned(byte[] b1, int s1, int l1, byte[] b2, int s2, int l2)
    {
        return BestHolder.BEST.compare(b1, s1, l1, b2, s2, l2);
    }

    public static int compareUnsigned(ByteBuffer b1, byte[] b2, int s2, int l2)
    {
        return BestHolder.BEST.compare(b1, b2, s2, l2);
    }

    public static int compareUnsigned(byte[] b1, int s1, int l1, ByteBuffer b2)
    {
        return -BestHolder.BEST.compare(b2, b1, s1, l1);
    }

    public static int compareUnsigned(ByteBuffer b1, ByteBuffer b2)
    {
        return BestHolder.BEST.compare(b1, b2);
    }

    public static void copy(ByteBuffer src, int srcPosition, byte[] trg, int trgPosition, int length)
    {
        BestHolder.BEST.copy(src, srcPosition, trg, trgPosition, length);
    }

    public static void copy(ByteBuffer src, int srcPosition, ByteBuffer trg, int trgPosition, int length)
    {
        BestHolder.BEST.copy(src, srcPosition, trg, trgPosition, length);
    }

    public interface ByteOperations
    {
        abstract public int compare(byte[] buffer1, int offset1, int length1,
                                    byte[] buffer2, int offset2, int length2);

        abstract public int compare(ByteBuffer buffer1, byte[] buffer2, int offset2, int length2);

        abstract public int compare(ByteBuffer buffer1, ByteBuffer buffer2);

        abstract public void copy(ByteBuffer src, int srcPosition, byte[] trg, int trgPosition, int length);

        abstract public void copy(ByteBuffer src, int srcPosition, ByteBuffer trg, int trgPosition, int length);
    }

    /**
     * Provides a lexicographical comparer implementation; either a Java
     * implementation or a faster implementation based on {@link Unsafe}.
     * <p/>
     * <p>Uses reflection to gracefully fall back to the Java implementation if
     * {@code Unsafe} isn't available.
     */
    private static class BestHolder
    {
        static final String UNSAFE_COMPARER_NAME = FastByteOperations.class.getName() + "$UnsafeOperations";
        static final ByteOperations BEST = getBest();

        /**
         * Returns the Unsafe-using Comparer, or falls back to the pure-Java
         * implementation if unable to do so.
         */
        static ByteOperations getBest()
        {
            String arch = System.getProperty("os.arch");
            boolean unaligned = arch.equals("i386") || arch.equals("x86")
                                || arch.equals("amd64") || arch.equals("x86_64");
            if (!unaligned)
                return new PureJavaOperations();
            try
            {
                Class<?> theClass = Class.forName(UNSAFE_COMPARER_NAME);

                // yes, UnsafeComparer does implement Comparer<byte[]>
                @SuppressWarnings("unchecked")
                ByteOperations comparer = (ByteOperations) theClass.getConstructor().newInstance();
                return comparer;
            }
            catch (Throwable t)
            {
                JVMStabilityInspector.inspectThrowable(t);
                // ensure we really catch *everything*
                return new PureJavaOperations();
            }
        }

    }

    @SuppressWarnings("unused") // used via reflection
    public static final class UnsafeOperations implements ByteOperations
    {
        static final Unsafe theUnsafe;
        /**
         * The offset to the first element in a byte array.
         */
        static final long BYTE_ARRAY_BASE_OFFSET;
        static final long DIRECT_BUFFER_ADDRESS_OFFSET;

        static
        {
            theUnsafe = (Unsafe) AccessController.doPrivileged(
                      new PrivilegedAction<Object>()
                      {
                          @Override
                          public Object run()
                          {
                              try
                              {
                                  Field f = Unsafe.class.getDeclaredField("theUnsafe");
                                  f.setAccessible(true);
                                  return f.get(null);
                              }
                              catch (NoSuchFieldException e)
                              {
                                  // It doesn't matter what we throw;
                                  // it's swallowed in getBest().
                                  throw new Error();
                              }
                              catch (IllegalAccessException e)
                              {
                                  throw new Error();
                              }
                          }
                      });

            try
            {
                BYTE_ARRAY_BASE_OFFSET = theUnsafe.arrayBaseOffset(byte[].class);
                DIRECT_BUFFER_ADDRESS_OFFSET = theUnsafe.objectFieldOffset(Buffer.class.getDeclaredField("address"));
            }
            catch (Exception e)
            {
                throw new AssertionError(e);
            }

            // sanity check - this should never fail
            if (theUnsafe.arrayIndexScale(byte[].class) != 1)
            {
                throw new AssertionError();
            }
        }

        static final boolean BIG_ENDIAN = ByteOrder.nativeOrder().equals(ByteOrder.BIG_ENDIAN);

        public int compare(byte[] buffer1, int offset1, int length1, byte[] buffer2, int offset2, int length2)
        {
            return compareTo(buffer1, BYTE_ARRAY_BASE_OFFSET + offset1, length1,
                             buffer2, BYTE_ARRAY_BASE_OFFSET + offset2, length2);
        }

        public int compare(ByteBuffer buffer1, byte[] buffer2, int offset2, int length2)
        {
            int position = buffer1.position();
            int limit = buffer1.limit();
            int length1 = limit - position;
            if (buffer1.hasArray())
            {
                long offset1 = BYTE_ARRAY_BASE_OFFSET + buffer1.arrayOffset() + position;
                return compareTo(buffer1.array(), offset1, length1, buffer2, BYTE_ARRAY_BASE_OFFSET + offset2, length2);
            }
            else
            {
                long address1 = theUnsafe.getLong(buffer1, DIRECT_BUFFER_ADDRESS_OFFSET) + position;
                return compareToNativeLeft(address1, length1, buffer2, BYTE_ARRAY_BASE_OFFSET + offset2, length2);
            }
        }

        public int compare(ByteBuffer buffer1, ByteBuffer buffer2)
        {
            return compareTo(buffer1, buffer2);
        }

        public void copy(ByteBuffer src, int srcPosition, byte[] trg, int trgPosition, int length)
        {
            if (src.hasArray())
                System.arraycopy(src.array(), src.arrayOffset() + srcPosition, trg, trgPosition, length);
            else
                copyNativeSrc(theUnsafe.getLong(src, DIRECT_BUFFER_ADDRESS_OFFSET) + srcPosition, trg, trgPosition, length);
        }

        public void copy(ByteBuffer srcBuf, int srcPosition, ByteBuffer trgBuf, int trgPosition, int length)
        {
            if (srcBuf.hasArray())
            {
                copy(srcBuf.array(), BYTE_ARRAY_BASE_OFFSET + srcBuf.arrayOffset() + srcPosition, trgBuf, trgPosition, length);
            }
            else
            {
                copyNativeSrc(theUnsafe.getLong(srcBuf, DIRECT_BUFFER_ADDRESS_OFFSET) + srcPosition, trgBuf, trgPosition, length);
            }
        }

        public static void copy(Object src, long srcOffset, ByteBuffer trgBuf, int trgPosition, int length)
        {
            assert src != null;
            if (trgBuf.hasArray())
                copy(src, srcOffset, trgBuf.array(), trgBuf.arrayOffset() + trgPosition, length);
            else
                copy(src, srcOffset, null, trgPosition + theUnsafe.getLong(trgBuf, DIRECT_BUFFER_ADDRESS_OFFSET), length);
        }

        public static void copyNativeSrc(long srcAddress, ByteBuffer trgBuf, int trgPosition, int length)
        {
            if (trgBuf.hasArray())
                copyNativeSrc(srcAddress, trgBuf.array(), trgBuf.arrayOffset() + trgPosition, length);
            else
                copy(null, srcAddress, null, trgPosition + theUnsafe.getLong(trgBuf, DIRECT_BUFFER_ADDRESS_OFFSET), length);
        }

        public static void copy(Object src, long srcOffset, byte[] trg, int trgPosition, int length)
        {
            assert src != null;
            if (length <= MIN_COPY_THRESHOLD)
            {
                for (int i = 0 ; i < length ; i++)
                    trg[trgPosition + i] = theUnsafe.getByte(src, srcOffset + i);
            }
            else
            {
                copy(src, srcOffset, trg, BYTE_ARRAY_BASE_OFFSET + trgPosition, length);
            }
        }

        public static void copyNativeSrc(long srcAddress, byte[] trg, int trgPosition, int length)
        {
            if (length <= MIN_COPY_THRESHOLD)
            {
                for (int i = 0 ; i < length ; i++)
                    trg[trgPosition + i] = theUnsafe.getByte(srcAddress + i);
            }
            else
            {
                copy(null, srcAddress, trg, BYTE_ARRAY_BASE_OFFSET + trgPosition, length);
            }
        }

        // 1M, copied from java.nio.Bits (unfortunately a package-private class)
        private static final long UNSAFE_COPY_THRESHOLD = 1 << 20;
        private static final long MIN_COPY_THRESHOLD = 6;

        public static void copy(Object src, long srcOffset, Object dst, long dstOffset, long length)
        {
            while (length > 0) {
                long size = (length > UNSAFE_COPY_THRESHOLD) ? UNSAFE_COPY_THRESHOLD : length;
                // if src or dst are null, the offsets are absolute base addresses:
                theUnsafe.copyMemory(src, srcOffset, dst, dstOffset, size);
                length -= size;
                srcOffset += size;
                dstOffset += size;
            }
        }

        @Inline
        public static int compareTo(ByteBuffer buffer1, ByteBuffer buffer2)
        {
            int length1 = buffer1.remaining();
            if (buffer1.hasArray())
            {
                long offset1 = BYTE_ARRAY_BASE_OFFSET + buffer1.arrayOffset() + buffer1.position();
                return compareTo(buffer1.array(), offset1, length1, buffer2);
            }
            else
            {
                long address1 = theUnsafe.getLong(buffer1, DIRECT_BUFFER_ADDRESS_OFFSET) + buffer1.position();
                return compareToNativeLeft(address1, length1, buffer2);
            }
        }

        @Inline
        public static int compareTo(Object buffer1, long offset1, int length1, ByteBuffer buffer)
        {
            int position = buffer.position();
            int limit = buffer.limit();
            int length2 = limit - position;
            if (buffer.hasArray())
            {
                long address = BYTE_ARRAY_BASE_OFFSET + buffer.arrayOffset() + position;
                return compareTo(buffer1, offset1, length1, buffer.array(), address, length2);
            }
            else
            {
                long address = theUnsafe.getLong(buffer, DIRECT_BUFFER_ADDRESS_OFFSET) + position;
                return -compareToNativeLeft(address, length2, buffer1, offset1, length1);
            }
        }

        @Inline
        public static int compareToNativeLeft(long address1, int length1, ByteBuffer buffer)
        {
            int position = buffer.position();
            int limit = buffer.limit();
            int length2 = limit - position;
            if (buffer.hasArray())
            {
                long offset2 = BYTE_ARRAY_BASE_OFFSET + buffer.arrayOffset() + position;
                return compareToNativeLeft(address1, length1, buffer.array(), offset2, length2);
            }
            else
            {
                long address2 = theUnsafe.getLong(buffer, DIRECT_BUFFER_ADDRESS_OFFSET) + position;
                return compareToNativeLeftAndRight(address1, length1, address2, length2);
            }

        }


        /**
         * Lexicographically compare two arrays.
         *
         * @param buffer1 left operand: a byte[]
         * @param buffer2 right operand: a byte[]
         * @param memoryOffset1 Where to start comparing in the left buffer
         * @param memoryOffset2 Where to start comparing in the right buffer
         * @param length1 How much to compare from the left buffer
         * @param length2 How much to compare from the right buffer
         * @return 0 if equal, < 0 if left is less than right, etc.
         */
        @Inline
        public static int compareTo(Object buffer1, long memoryOffset1, int length1,
                             Object buffer2, long memoryOffset2, int length2)
        {
            assert buffer1 != null;
            assert buffer2 != null;
            int minLength = Math.min(length1, length2);

            /*
             * Compare 8 bytes at a time. Benchmarking shows comparing 8 bytes at a
             * time is no slower than comparing 4 bytes at a time even on 32-bit.
             * On the other hand, it is substantially faster on 64-bit.
             */
            int wordComparisons = minLength & ~7;
            for (int i = 0; i < wordComparisons ; i += Longs.BYTES)
            {
                long lw = theUnsafe.getLong(buffer1, memoryOffset1 + (long) i);
                long rw = theUnsafe.getLong(buffer2, memoryOffset2 + (long) i);

                if (lw != rw)
                {
                    if (BIG_ENDIAN)
                        return UnsignedLongs.compare(lw, rw);

                    return UnsignedLongs.compare(Long.reverseBytes(lw), Long.reverseBytes(rw));
                }
            }

            for (int i = wordComparisons ; i < minLength ; i++)
            {
                int b1 = theUnsafe.getByte(buffer1, memoryOffset1 + i) & 0xFF;
                int b2 = theUnsafe.getByte(buffer2, memoryOffset2 + i) & 0xFF;
                if (b1 != b2)
                    return b1 - b2;
            }

            return length1 - length2;
        }

        /**
         * Lexicographically compare two byte sequences.
         *
         * @param address1 Where to start comparing in the left buffer
         * @param buffer2 right operand: a byte[]
         * @param memoryOffset2 Where to start comparing in the right buffer
         * @param length1 How much to compare from the left buffer
         * @param length2 How much to compare from the right buffer
         * @return 0 if equal, < 0 if left is less than right, etc.
         */
        @Inline
        public static int compareToNativeLeft(long address1, int length1,
                                              Object buffer2, long memoryOffset2, int length2)
        {
            assert buffer2 != null;
            int minLength = Math.min(length1, length2);

            /*
             * Compare 8 bytes at a time. Benchmarking shows comparing 8 bytes at a
             * time is no slower than comparing 4 bytes at a time even on 32-bit.
             * On the other hand, it is substantially faster on 64-bit.
             */
            int wordComparisons = minLength & ~7;
            for (int i = 0; i < wordComparisons ; i += Longs.BYTES)
            {
                long lw = theUnsafe.getLong(address1 + (long) i);
                long rw = theUnsafe.getLong(buffer2, memoryOffset2 + (long) i);

                if (lw != rw)
                {
                    if (BIG_ENDIAN)
                        return UnsignedLongs.compare(lw, rw);

                    return UnsignedLongs.compare(Long.reverseBytes(lw), Long.reverseBytes(rw));
                }
            }

            for (int i = wordComparisons ; i < minLength ; i++)
            {
                int b1 = theUnsafe.getByte(address1 + i) & 0xFF;
                int b2 = theUnsafe.getByte(buffer2, memoryOffset2 + i) & 0xFF;
                if (b1 != b2)
                    return b1 - b2;
            }

            return length1 - length2;
        }

        /**
         * Lexicographically compare two byte sequences.
         *
         * @param address1 Where to start comparing in the left buffer
         * @param address2 Where to start comparing in the right buffer
         * @param length1 How much to compare from the left buffer
         * @param length2 How much to compare from the right buffer
         * @return 0 if equal, < 0 if left is less than right, etc.
         */
        @Inline
        public static int compareToNativeLeftAndRight(long address1, int length1,
                                                      long address2, int length2)
        {
            int minLength = Math.min(length1, length2);

            /*
             * Compare 8 bytes at a time. Benchmarking shows comparing 8 bytes at a
             * time is no slower than comparing 4 bytes at a time even on 32-bit.
             * On the other hand, it is substantially faster on 64-bit.
             */
            int wordComparisons = minLength & ~7;
            for (int i = 0; i < wordComparisons ; i += Longs.BYTES)
            {
                long lw = theUnsafe.getLong(address1 + (long) i);
                long rw = theUnsafe.getLong(address2 + (long) i);

                if (lw != rw)
                {
                    if (BIG_ENDIAN)
                        return UnsignedLongs.compare(lw, rw);

                    return UnsignedLongs.compare(Long.reverseBytes(lw), Long.reverseBytes(rw));
                }
            }

            for (int i = wordComparisons ; i < minLength ; i++)
            {
                int b1 = theUnsafe.getByte(address1 + i) & 0xFF;
                int b2 = theUnsafe.getByte(address2 + i) & 0xFF;
                if (b1 != b2)
                    return b1 - b2;
            }

            return length1 - length2;
        }
    }

    @SuppressWarnings("unused")
    public static final class PureJavaOperations implements FastByteOperations.ByteOperations
    {
        @Override
        public int compare(byte[] buffer1, int offset1, int length1,
                           byte[] buffer2, int offset2, int length2)
        {
            // Short circuit equal case
            if (buffer1 == buffer2 && offset1 == offset2 && length1 == length2)
                return 0;

            int end1 = offset1 + length1;
            int end2 = offset2 + length2;
            for (int i = offset1, j = offset2; i < end1 && j < end2; i++, j++)
            {
                int a = (buffer1[i] & 0xff);
                int b = (buffer2[j] & 0xff);
                if (a != b)
                {
                    return a - b;
                }
            }
            return length1 - length2;
        }

        public int compare(ByteBuffer buffer1, byte[] buffer2, int offset2, int length2)
        {
            if (buffer1.hasArray())
                return compare(buffer1.array(), buffer1.arrayOffset() + buffer1.position(), buffer1.remaining(),
                               buffer2, offset2, length2);
            return compare(buffer1, ByteBuffer.wrap(buffer2, offset2, length2));
        }

        public int compare(ByteBuffer buffer1, ByteBuffer buffer2)
        {
            int end1 = buffer1.limit();
            int end2 = buffer2.limit();
            for (int i = buffer1.position(), j = buffer2.position(); i < end1 && j < end2; i++, j++)
            {
                int a = (buffer1.get(i) & 0xff);
                int b = (buffer2.get(j) & 0xff);
                if (a != b)
                {
                    return a - b;
                }
            }
            return buffer1.remaining() - buffer2.remaining();
        }

        public void copy(ByteBuffer src, int srcPosition, byte[] trg, int trgPosition, int length)
        {
            if (src.hasArray())
            {
                System.arraycopy(src.array(), src.arrayOffset() + srcPosition, trg, trgPosition, length);
                return;
            }
            src = src.duplicate();
            src.position(srcPosition);
            src.get(trg, trgPosition, length);
        }

        public void copy(ByteBuffer src, int srcPosition, ByteBuffer trg, int trgPosition, int length)
        {
            if (src.hasArray() && trg.hasArray())
            {
                System.arraycopy(src.array(), src.arrayOffset() + srcPosition, trg.array(), trg.arrayOffset() + trgPosition, length);
                return;
            }
            src = src.duplicate();
            src.position(srcPosition).limit(srcPosition + length);
            trg = trg.duplicate();
            trg.position(trgPosition);
            trg.put(src);
        }
    }
}
