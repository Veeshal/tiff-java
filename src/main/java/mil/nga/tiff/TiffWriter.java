package mil.nga.tiff;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import mil.nga.tiff.compression.CompressionEncoder;
import mil.nga.tiff.compression.DeflateCompression;
import mil.nga.tiff.compression.LZWCompression;
import mil.nga.tiff.compression.PackbitsCompression;
import mil.nga.tiff.compression.RawCompression;
import mil.nga.tiff.io.ByteWriter;
import mil.nga.tiff.io.IOUtils;
import mil.nga.tiff.util.TiffConstants;
import mil.nga.tiff.util.TiffException;

public class TiffWriter {

	/**
	 * Write a TIFF to a file
	 * 
	 * @param file
	 *            file to create
	 * @param tiffImage
	 *            TIFF image
	 * @throws IOException
	 *             upon failure to write
	 */
	public static void writeTiff(File file, TIFFImage tiffImage)
			throws IOException {
		ByteWriter writer = new ByteWriter();
		writeTiff(file, writer, tiffImage);
		writer.close();
	}

	/**
	 * Write a TIFF to a file
	 * 
	 * @param file
	 *            file to create
	 * @param writer
	 *            byte writer
	 * @param tiffImage
	 *            TIFF Image
	 * @throws IOException
	 *             upon failure to write
	 */
	public static void writeTiff(File file, ByteWriter writer,
			TIFFImage tiffImage) throws IOException {
		byte[] bytes = writeTiffToBytes(writer, tiffImage);
		ByteArrayInputStream inputStream = new ByteArrayInputStream(bytes);
		IOUtils.copyStream(inputStream, file);
	}

	/**
	 * Write a TIFF to bytes
	 * 
	 * @param tiffImage
	 *            TIFF image
	 * @return tiff bytes
	 * @throws IOException
	 *             upon failure to write
	 */
	public static byte[] writeTiffToBytes(TIFFImage tiffImage)
			throws IOException {
		ByteWriter writer = new ByteWriter();
		byte[] bytes = writeTiffToBytes(writer, tiffImage);
		writer.close();
		return bytes;
	}

	/**
	 * Write a TIFF to bytes
	 * 
	 * @param writer
	 *            byte writer
	 * @param tiffImage
	 *            TIFF image
	 * @return tiff bytes
	 * @throws IOException
	 *             upon failure to write
	 */
	public static byte[] writeTiffToBytes(ByteWriter writer, TIFFImage tiffImage)
			throws IOException {
		writeTiff(writer, tiffImage);
		byte[] bytes = writer.getBytes();
		return bytes;
	}

	/**
	 * Write a TIFF to a byte writer
	 * 
	 * @param writer
	 *            byte writer
	 * @param tiffImage
	 *            TIFF image
	 * @throws IOException
	 *             upon failure to write
	 */
	public static void writeTiff(ByteWriter writer, TIFFImage tiffImage)
			throws IOException {

		// Write the byte order (bytes 0-1)
		String byteOrder = writer.getByteOrder() == ByteOrder.BIG_ENDIAN ? TiffConstants.BYTE_ORDER_BIG_ENDIAN
				: TiffConstants.BYTE_ORDER_LITTLE_ENDIAN;
		writer.writeString(byteOrder);

		// Write the TIFF file identifier (bytes 2-3)
		writer.writeUnsignedShort(TiffConstants.FILE_IDENTIFIER);

		// Write the first IFD offset (bytes 4-7), set to start right away at
		// byte 8
		writer.writeUnsignedInt(TiffConstants.HEADER_BYTES);

		// Write the TIFF Image
		writeImageFileDirectories(writer, tiffImage);
	}

	/**
	 * Write the image file directories
	 * 
	 * @param writer
	 *            byte writer
	 * @param tiffImage
	 *            tiff image
	 * @throws IOException
	 */
	private static void writeImageFileDirectories(ByteWriter writer,
			TIFFImage tiffImage) throws IOException {

		// Write each file directory
		for (int i = 0; i < tiffImage.getFileDirectories().size(); i++) {
			FileDirectory fileDirectory = tiffImage.getFileDirectories().get(i);

			// Populate strip entries with placeholder values so the sizes come
			// out correctly
			populateStripEntries(fileDirectory);

			// Track of the starting byte of this directory
			int startOfDirectory = writer.size();
			long afterDirectory = startOfDirectory + fileDirectory.size();
			long afterValues = startOfDirectory
					+ fileDirectory.sizeWithValues();

			// Write the number of directory entries
			writer.writeUnsignedShort(fileDirectory.numEntries());

			List<FileDirectoryEntry> entryValues = new ArrayList<>();

			// Byte to write the next values
			long nextByte = afterDirectory;

			List<Long> valueBytesCheck = new ArrayList<>();

			// Write the raster bytes to temporary storage
			if (fileDirectory.getStripOffsets() == null) {
				throw new TiffException("Tiled images are not supported");
			}
			Rasters rasters = fileDirectory.getWriteRasters();
			ByteWriter rasterWriter = new ByteWriter(writer.getByteOrder());
			writeRasters(rasterWriter, fileDirectory, afterValues);
			rasters.setWriteBytes(rasterWriter.getBytes());
			rasterWriter.close();

			// Write each entry
			for (FileDirectoryEntry entry : fileDirectory.getEntries()) {
				writer.writeUnsignedShort(entry.getFieldTag().getId());
				writer.writeUnsignedShort(entry.getFieldType().getValue());
				writer.writeUnsignedInt(entry.getTypeCount());
				long valueBytes = entry.getFieldType().getBytes()
						* entry.getTypeCount();
				if (valueBytes > 4) {
					// Write the value offset
					entryValues.add(entry);
					writer.writeUnsignedInt(nextByte);
					valueBytesCheck.add(nextByte);
					nextByte += entry.sizeOfValues();
				} else {
					// Write the value in the inline 4 byte space, left aligned
					int bytesWritten = writeValues(writer, entry);
					if (bytesWritten != valueBytes) {
						throw new TiffException(
								"Unexpected bytes written. Expected: "
										+ valueBytes + ", Actual: "
										+ bytesWritten);
					}
					writeFillerBytes(writer, 4 - valueBytes);
				}
			}

			if (i + 1 == tiffImage.getFileDirectories().size()) {
				// Write 0's since there are not more file directories
				writeFillerBytes(writer, 4);
			} else {
				// Write the start address of the next file directory
				long nextFileDirectory = afterValues
						+ rasters.getWriteBytes().length;
				writer.writeUnsignedInt(nextFileDirectory);
			}

			// Write the external entry values
			for (int entryIndex = 0; entryIndex < entryValues.size(); entryIndex++) {
				FileDirectoryEntry entry = entryValues.get(entryIndex);
				long entryValuesByte = valueBytesCheck.get(entryIndex);
				if (entryValuesByte != writer.size()) {
					throw new TiffException(
							"Entry values byte does not match the write location. Entry Values Byte: "
									+ entryValuesByte + ", Current Byte: "
									+ writer.size());
				}
				int bytesWritten = writeValues(writer, entry);
				long valueBytes = entry.getFieldType().getBytes()
						* entry.getTypeCount();
				if (bytesWritten != valueBytes) {
					throw new TiffException(
							"Unexpected bytes written. Expected: " + valueBytes
									+ ", Actual: " + bytesWritten);
				}
			}

			// Write the image bytes
			writer.writeBytes(rasters.getWriteBytes());
		}

	}

	/**
	 * Populate the strip entry values with placeholder values for correct size
	 * calculations
	 * 
	 * @param fileDirectory
	 *            file directory
	 */
	private static void populateStripEntries(FileDirectory fileDirectory) {

		Rasters rasters = fileDirectory.getWriteRasters();

		int rowsPerStrip = fileDirectory.getRowsPerStrip().intValue();
		int stripsPerSample = (int) Math.ceil(fileDirectory.getImageHeight()
				.doubleValue() / rowsPerStrip);
		int strips = stripsPerSample;
		if (fileDirectory.getPlanarConfiguration() == TiffConstants.PLANAR_CONFIGURATION_PLANAR) {
			strips *= rasters.getSamplesPerPixel();
		}

		List<Long> stripOffsets = new ArrayList<>();
		List<Integer> stripByteCounts = new ArrayList<>();

		// Set placeholder values
		for (int strip = 0; strip < strips; strip++) {
			stripOffsets.add(0l);
			stripByteCounts.add(0);
		}

		fileDirectory.setStripOffsetsAsLongs(stripOffsets);
		fileDirectory.setStripByteCounts(stripByteCounts);
	}

	/**
	 * Write the rasters
	 * 
	 * @param writer
	 *            byte writer
	 * @param fileDirectory
	 *            file directory
	 * @param offset
	 *            byte offset
	 * @throws IOException
	 */
	private static void writeRasters(ByteWriter writer,
			FileDirectory fileDirectory, long offset) throws IOException {

		Rasters rasters = fileDirectory.getWriteRasters();

		FieldType[] sampleFieldTypes = new FieldType[rasters
				.getSamplesPerPixel()];
		for (int sample = 0; sample < rasters.getSamplesPerPixel(); sample++) {
			sampleFieldTypes[sample] = fileDirectory
					.getFieldTypeForSample(sample);
		}

		// Get the compression encoder
		CompressionEncoder encoder = getEncoder(fileDirectory);

		int rowsPerStrip = fileDirectory.getRowsPerStrip().intValue();
		int stripsPerSample = (int) Math.ceil(fileDirectory.getImageHeight()
				.doubleValue() / rowsPerStrip);
		int strips = stripsPerSample;
		if (fileDirectory.getPlanarConfiguration() == TiffConstants.PLANAR_CONFIGURATION_PLANAR) {
			strips *= rasters.getSamplesPerPixel();
		}

		List<Long> stripOffsets = new ArrayList<>();
		List<Integer> stripByteCounts = new ArrayList<>();

		// Write each strip
		for (int strip = 0; strip < strips; strip++) {

			int startingY;
			Integer sample = null;
			if (fileDirectory.getPlanarConfiguration() == TiffConstants.PLANAR_CONFIGURATION_PLANAR) {
				sample = strip / stripsPerSample;
				startingY = (strip % stripsPerSample) * rowsPerStrip;
			} else {
				startingY = strip * rowsPerStrip;
			}

			// Write the strip of bytes
			ByteWriter stripWriter = new ByteWriter(writer.getByteOrder());

			int endingY = startingY
					+ fileDirectory.getRowsPerStrip().intValue();
			for (int y = startingY; y < endingY; y++) {

				ByteWriter rowWriter = new ByteWriter(writer.getByteOrder());

				for (int x = 0; x < fileDirectory.getImageWidth().intValue(); x++) {

					if (sample != null) {
						Number value = rasters.getPixelSample(sample, x, y);
						FieldType fieldType = sampleFieldTypes[sample];
						writeValue(rowWriter, fieldType, value);
					} else {
						Number[] values = rasters.getPixel(x, y);
						for (int sampleIndex = 0; sampleIndex < values.length; sampleIndex++) {
							Number value = values[sampleIndex];
							FieldType fieldType = sampleFieldTypes[sampleIndex];
							writeValue(rowWriter, fieldType, value);
						}
					}
				}

				// Encode the bytes
				byte[] rowBytes = rowWriter.getBytes();
				rowWriter.close();
				byte[] encodedRow = encoder.encodeBlock(rowBytes,
						writer.getByteOrder());

				// Write the row
				stripWriter.writeBytes(encodedRow);
			}

			// Get and write the strip bytes
			byte[] stripBytes = stripWriter.getBytes();
			stripWriter.close();
			writer.writeBytes(stripBytes);

			int bytesWritten = stripBytes.length;
			stripByteCounts.add(bytesWritten);

			stripOffsets.add(offset);
			offset += bytesWritten;

		}

		fileDirectory.setStripOffsetsAsLongs(stripOffsets);
		fileDirectory.setStripByteCounts(stripByteCounts);

	}

	/**
	 * Get the compression encoder
	 * 
	 * @param fileDirectory
	 *            file directory
	 * @return encoder
	 */
	private static CompressionEncoder getEncoder(FileDirectory fileDirectory) {

		CompressionEncoder encoder = null;

		// Determine the encoder based upon the compression
		Integer compression = fileDirectory.getCompression();
		if (compression == null) {
			compression = TiffConstants.COMPRESSION_NO;
		}
		switch (compression) {
		case TiffConstants.COMPRESSION_NO:
			encoder = new RawCompression();
			break;
		case TiffConstants.COMPRESSION_CCITT_HUFFMAN:
			throw new TiffException("CCITT Huffman compression not supported: "
					+ compression);
		case TiffConstants.COMPRESSION_T4:
			throw new TiffException("T4-encoding compression not supported: "
					+ compression);
		case TiffConstants.COMPRESSION_T6:
			throw new TiffException("T6-encoding compression not supported: "
					+ compression);
		case TiffConstants.COMPRESSION_LZW:
			encoder = new LZWCompression();
			break;
		case TiffConstants.COMPRESSION_JPEG_OLD:
		case TiffConstants.COMPRESSION_JPEG_NEW:
			throw new TiffException("JPEG compression not supported: "
					+ compression);
		case TiffConstants.COMPRESSION_DEFLATE:
			encoder = new DeflateCompression();
			break;
		case TiffConstants.COMPRESSION_PACKBITS:
			encoder = new PackbitsCompression();
			break;
		default:
			throw new TiffException("Unknown compression method identifier: "
					+ compression);
		}

		return encoder;
	}

	/**
	 * Write the value according to the field type
	 * 
	 * @param writer
	 *            byte writer
	 * @param fieldType
	 *            field type
	 * @throws IOException
	 */
	private static void writeValue(ByteWriter writer, FieldType fieldType,
			Number value) throws IOException {

		switch (fieldType) {
		case BYTE:
			writer.writeUnsignedByte(value.shortValue());
			break;
		case SHORT:
			writer.writeUnsignedShort(value.intValue());
			break;
		case LONG:
			writer.writeUnsignedInt(value.longValue());
			break;
		case SBYTE:
			writer.writeByte(value.byteValue());
			break;
		case SSHORT:
			writer.writeShort(value.shortValue());
			break;
		case SLONG:
			writer.writeInt(value.intValue());
			break;
		case FLOAT:
			writer.writeFloat(value.floatValue());
			break;
		case DOUBLE:
			writer.writeDouble(value.doubleValue());
			break;
		default:
			throw new TiffException("Unsupported raster field type: "
					+ fieldType);
		}

	}

	/**
	 * Write filler 0 bytes
	 * 
	 * @param writer
	 *            byte writer
	 * @param count
	 *            number of 0 bytes to write
	 */
	private static void writeFillerBytes(ByteWriter writer, long count) {
		for (long i = 0; i < count; i++) {
			writer.writeUnsignedByte((short) 0);
		}
	}

	/**
	 * Write file directory entry values
	 * 
	 * @param writer
	 *            byte writer
	 * @param entry
	 *            file directory entry
	 * @return bytes written
	 * @throws IOException
	 */
	@SuppressWarnings("unchecked")
	private static int writeValues(ByteWriter writer, FileDirectoryEntry entry)
			throws IOException {

		List<Object> valuesList = null;
		if (entry.getTypeCount() == 1
				&& !entry.getFieldTag().isArray()
				&& !(entry.getFieldType() == FieldType.RATIONAL || entry
						.getFieldType() == FieldType.SRATIONAL)) {
			valuesList = new ArrayList<>();
			valuesList.add(entry.getValues());
		} else {
			valuesList = (List<Object>) entry.getValues();
		}

		int bytesWritten = 0;

		for (Object value : valuesList) {

			switch (entry.getFieldType()) {
			case ASCII:
				bytesWritten += writer.writeString((String) value);
				if (bytesWritten < entry.getTypeCount()) {
					writeFillerBytes(writer, 1);
					bytesWritten++;
				}
				break;
			case BYTE:
			case UNDEFINED:
				writer.writeUnsignedByte((short) value);
				bytesWritten += 1;
				break;
			case SBYTE:
				writer.writeByte((byte) value);
				bytesWritten += 1;
				break;
			case SHORT:
				writer.writeUnsignedShort((int) value);
				bytesWritten += 2;
				break;
			case SSHORT:
				writer.writeShort((short) value);
				bytesWritten += 2;
				break;
			case LONG:
				writer.writeUnsignedInt((long) value);
				bytesWritten += 4;
				break;
			case SLONG:
				writer.writeInt((int) value);
				bytesWritten += 4;
				break;
			case RATIONAL:
				writer.writeUnsignedInt((long) value);
				bytesWritten += 4;
				break;
			case SRATIONAL:
				writer.writeInt((int) value);
				bytesWritten += 4;
				break;
			case FLOAT:
				writer.writeFloat((float) value);
				bytesWritten += 4;
				break;
			case DOUBLE:
				writer.writeDouble((double) value);
				bytesWritten += 8;
				break;
			default:
				throw new TiffException("Invalid field type: "
						+ entry.getFieldType());
			}

		}

		return bytesWritten;
	}

}
