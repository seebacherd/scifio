/*
 * #%L
 * SCIFIO library for reading and converting scientific file formats.
 * %%
 * Copyright (C) 2011 - 2015 Board of Regents of the University of
 * Wisconsin-Madison
 * %%
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */

package io.scif.codec;

import io.scif.FormatException;
import io.scif.common.DataTools;
import io.scif.gui.AWTImageTools;
import io.scif.io.RandomAccessInputStream;

import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;

import javax.imageio.ImageIO;

import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

/**
 * This class implements JPEG compression and decompression.
 */
@Plugin(type = Codec.class)
public class JPEGCodec extends AbstractCodec {

	@Parameter
	private CodecService codecService;

	/**
	 * The CodecOptions parameter should have the following fields set:
	 * {@link CodecOptions#width width} {@link CodecOptions#height height}
	 * {@link CodecOptions#channels channels} {@link CodecOptions#bitsPerSample
	 * bitsPerSample} {@link CodecOptions#interleaved interleaved}
	 * {@link CodecOptions#littleEndian littleEndian} {@link CodecOptions#signed
	 * signed}
	 *
	 * @see Codec#compress(byte[], CodecOptions)
	 */
	@Override
	public byte[] compress(final byte[] data, CodecOptions options)
		throws FormatException
	{
		if (data == null || data.length == 0) return data;
		if (options == null) options = CodecOptions.getDefaultOptions();

		if (options.bitsPerSample > 8) {
			throw new FormatException("> 8 bit data cannot be compressed with JPEG.");
		}

		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		final BufferedImage img =
			AWTImageTools.makeImage(data, options.width, options.height,
				options.channels, options.interleaved, options.bitsPerSample / 8,
				false, options.littleEndian, options.signed);

		try {
			ImageIO.write(img, "jpeg", out);
		}
		catch (final IOException e) {
			throw new FormatException("Could not write JPEG data", e);
		}
		return out.toByteArray();
	}

	/**
	 * The CodecOptions parameter should have the following fields set:
	 * {@link CodecOptions#interleaved interleaved}
	 * {@link CodecOptions#littleEndian littleEndian}
	 *
	 * @see Codec#decompress(RandomAccessInputStream, CodecOptions)
	 */
	@Override
	public byte[] decompress(final RandomAccessInputStream in,
		CodecOptions options) throws FormatException, IOException
	{
		BufferedImage b;
		final long fp = in.getFilePointer();
		try {
			try {
				while (in.read() != (byte) 0xff || in.read() != (byte) 0xd8) {
					/* Read to data. */
				}
				in.seek(in.getFilePointer() - 2);
			}
			catch (final EOFException e) {
				in.seek(fp);
			}

			b = ImageIO.read(new BufferedInputStream(new DataInputStream(in), 8192));
		}
		catch (final IOException exc) {
			// probably a lossless JPEG; delegate to LosslessJPEGCodec
			in.seek(fp);
			final Codec codec = codecService.getCodec(LosslessJPEGCodec.class);
			return codec.decompress(in, options);
		}

		if (options == null) options = CodecOptions.getDefaultOptions();

		final byte[][] buf = AWTImageTools.getPixelBytes(b, options.littleEndian);

		// correct for YCbCr encoding, if necessary
		if (options.ycbcr && buf.length == 3) {
			final int nBytes = buf[0].length / (b.getWidth() * b.getHeight());
			final int mask = (int) (Math.pow(2, nBytes * 8) - 1);
			for (int i = 0; i < buf[0].length; i += nBytes) {
				final int y =
					DataTools.bytesToInt(buf[0], i, nBytes, options.littleEndian);
				int cb = DataTools.bytesToInt(buf[1], i, nBytes, options.littleEndian);
				int cr = DataTools.bytesToInt(buf[2], i, nBytes, options.littleEndian);

				cb = Math.max(0, cb - 128);
				cr = Math.max(0, cr - 128);

				final int red = (int) (y + 1.402 * cr) & mask;
				final int green = (int) (y - 0.34414 * cb - 0.71414 * cr) & mask;
				final int blue = (int) (y + 1.772 * cb) & mask;

				DataTools.unpackBytes(red, buf[0], i, nBytes, options.littleEndian);
				DataTools.unpackBytes(green, buf[1], i, nBytes, options.littleEndian);
				DataTools.unpackBytes(blue, buf[2], i, nBytes, options.littleEndian);
			}
		}

		byte[] rtn = new byte[buf.length * buf[0].length];
		if (buf.length == 1) rtn = buf[0];
		else {
			if (options.interleaved) {
				int next = 0;
				for (int i = 0; i < buf[0].length; i++) {
					for (int j = 0; j < buf.length; j++) {
						rtn[next++] = buf[j][i];
					}
				}
			}
			else {
				for (int i = 0; i < buf.length; i++) {
					System.arraycopy(buf[i], 0, rtn, i * buf[0].length, buf[i].length);
				}
			}
		}
		return rtn;
	}
}
