/**
 * Copyright (c) 2018--2020, Saalfeld lab
 * All rights reserved.
 *
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
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package org.janelia.saalfeldlab.n5.ij;

import ij.IJ;
import ij.ImagePlus;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.util.Intervals;
import net.imglib2.view.IntervalView;
import net.imglib2.view.SubsampleIntervalView;
import net.imglib2.view.Views;
import org.janelia.saalfeldlab.n5.Compression;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.Lz4Compression;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.RawCompression;
import org.janelia.saalfeldlab.n5.XzCompression;
import org.janelia.saalfeldlab.n5.blosc.BloscCompression;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.janelia.saalfeldlab.n5.universe.N5Factory;
import org.janelia.saalfeldlab.n5.universe.metadata.MetadataUtils;
import org.janelia.saalfeldlab.n5.universe.metadata.N5CosemMetadataParser;
import org.janelia.saalfeldlab.n5.universe.metadata.N5DatasetMetadata;
import org.janelia.saalfeldlab.n5.universe.metadata.N5Metadata;
import org.janelia.saalfeldlab.n5.universe.metadata.N5MetadataWriter;
import org.janelia.saalfeldlab.n5.universe.metadata.N5SingleScaleMetadataParser;
import org.janelia.saalfeldlab.n5.universe.metadata.axes.Axis;
import org.janelia.saalfeldlab.n5.universe.metadata.ome.ngff.v04.NgffSingleScaleAxesMetadata;
import org.janelia.saalfeldlab.n5.universe.metadata.ome.ngff.v04.NgffSingleScaleMetadataParser;
import org.janelia.saalfeldlab.n5.universe.metadata.ome.ngff.v04.OmeNgffMetadata;
import org.janelia.saalfeldlab.n5.universe.metadata.ome.ngff.v04.OmeNgffMetadataParser;
import org.janelia.saalfeldlab.n5.universe.metadata.ome.ngff.v04.OmeNgffMultiScaleMetadata;
import org.janelia.saalfeldlab.n5.universe.metadata.ome.ngff.v04.OmeNgffMultiScaleMetadata.OmeNgffDataset;
import org.janelia.saalfeldlab.n5.metadata.imagej.CosemToImagePlus;
import org.janelia.saalfeldlab.n5.metadata.imagej.ImagePlusLegacyMetadataParser;
import org.janelia.saalfeldlab.n5.metadata.imagej.ImagePlusMetadataTemplate;
import org.janelia.saalfeldlab.n5.metadata.imagej.ImageplusMetadata;
import org.janelia.saalfeldlab.n5.metadata.imagej.MetadataTemplateMapper;
import org.janelia.saalfeldlab.n5.metadata.imagej.N5ViewerToImagePlus;
import org.janelia.saalfeldlab.n5.metadata.imagej.NgffToImagePlus;
import org.janelia.saalfeldlab.n5.ui.N5MetadataSpecDialog;
import org.scijava.ItemVisibility;
import org.scijava.app.StatusService;
import org.scijava.command.Command;
import org.scijava.command.ContextCommand;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.ui.UIService;

import java.awt.event.WindowEvent;
import java.awt.event.WindowListener;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Plugin(type = Command.class, menuPath = "File>Save As>Export HDF5/N5/Zarr")
public class N5Exporter extends ContextCommand implements WindowListener {

  public static final String GZIP_COMPRESSION = "gzip";
  public static final String RAW_COMPRESSION = "raw";
  public static final String LZ4_COMPRESSION = "lz4";
  public static final String XZ_COMPRESSION = "xz";
  public static final String BLOSC_COMPRESSION = "blosc";

  public static final String NONE = "None";

  public static final String NO_OVERWRITE = "No overwrite";
  public static final String OVERWRITE = "Overwrite";
  public static final String WRITE_SUBSET = "Overwrite subset";

  public static enum OVERWRITE_OPTIONS {NO_OVERWRITE, OVERWRITE, WRITE_SUBSET}

  @Parameter(visibility = ItemVisibility.MESSAGE, required = false)
  private final String message = "Export an ImagePlus to an HDF5, N5, or Zarr container.";

  @Parameter
  private LogService log;

  @Parameter
  private StatusService status;

  @Parameter
  private UIService ui;

  @Parameter(label = "Image")
  private ImagePlus image; // or use Dataset? - maybe later

  @Parameter(label = "N5 root url")
  private String n5RootLocation;

  @Parameter(
		  label = "Dataset",
		  required = false,
		  description = "This argument is ignored if the N5ViewerMetadata style is selected")
  private String n5Dataset;

  @Parameter(label = "Block size")
  private String blockSizeArg;

  @Parameter(
		  label = "Compression",
		  choices = {GZIP_COMPRESSION, RAW_COMPRESSION, LZ4_COMPRESSION, XZ_COMPRESSION, BLOSC_COMPRESSION},
		  style = "listBox")
  private String compressionArg = GZIP_COMPRESSION;

  @Parameter(
		  label = "metadata type",
		  description = "The style for metadata to be stored in the exported N5.",
		  choices = {
				  N5Importer.MetadataOmeZarrKey,
				  N5Importer.MetadataN5ViewerKey,
				  N5Importer.MetadataN5CosemKey,
				  N5Importer.MetadataImageJKey,
				  N5Importer.MetadataCustomKey,
				  NONE})
  private String metadataStyle = N5Importer.MetadataOmeZarrKey;

  @Parameter(label = "Thread count", required = true, min = "1", max = "256")
  private int nThreads = 1;

  @Parameter(
		  label = "Overwrite options", required = true,
		  choices = {NO_OVERWRITE, OVERWRITE, WRITE_SUBSET},
		  description = "Determines whether overwriting datasets allows, and how overwriting occurs."
				  + "If selected will overwrite values in an existing dataset if they exist.")
  private String overwriteChoices = NO_OVERWRITE;

  @Parameter(label = "Overwrite subset offset", required = false,
		  description = "The point in pixel units where the origin of this image will be written into the n5-dataset (comma-delimited)")
  private String subsetOffset;

  private int[] blockSize;

  private final Map<String, N5MetadataWriter<?>> styles;

  private ImageplusMetadata<?> impMeta;

  private N5MetadataSpecDialog metaSpecDialog;

  private final HashMap<Class<?>, ImageplusMetadata<?>> impMetaWriterTypes;

  public N5Exporter() {

	styles = new HashMap<String, N5MetadataWriter<?>>();
	styles.put(N5Importer.MetadataOmeZarrKey, new OmeNgffMetadataParser());
	styles.put(N5Importer.MetadataN5ViewerKey, new N5SingleScaleMetadataParser());
	styles.put(N5Importer.MetadataN5CosemKey, new N5CosemMetadataParser());
	styles.put(N5Importer.MetadataImageJKey, new ImagePlusLegacyMetadataParser());

	// default image plus metadata writers
	impMetaWriterTypes = new HashMap<Class<?>, ImageplusMetadata<?>>();
	impMetaWriterTypes.put(ImagePlusLegacyMetadataParser.class, new ImagePlusLegacyMetadataParser());
	impMetaWriterTypes.put(N5CosemMetadataParser.class, new CosemToImagePlus());
	impMetaWriterTypes.put(N5SingleScaleMetadataParser.class, new N5ViewerToImagePlus());
	impMetaWriterTypes.put(NgffSingleScaleAxesMetadata.class, new NgffToImagePlus());
  }

  public void setOptions(
		  final ImagePlus image,
		  final String n5RootLocation,
		  final String n5Dataset,
		  final String blockSizeArg,
		  final String metadataStyle,
		  final String compression,
		  final String overwriteOption,
		  final String subsetOffset) {

	this.image = image;
	this.n5RootLocation = n5RootLocation;

	this.n5Dataset = n5Dataset;

	this.blockSizeArg = blockSizeArg;
	this.metadataStyle = metadataStyle;
	this.compressionArg = compression;

	this.overwriteChoices = overwriteOption;
	this.subsetOffset = subsetOffset;
  }

	/**
	 * Set the custom metadata mapper to use programmically.
	 *
	 * @param metadataMapper the metadata template mapper
	 */
	public void setMetadataMapper(final MetadataTemplateMapper metadataMapper) {

		 styles.put(N5Importer.MetadataCustomKey, metadataMapper);
		 impMetaWriterTypes.put(MetadataTemplateMapper.class, new ImagePlusMetadataTemplate());
	}

	public void parseBlockSize() {

		final int nd = image.getNDimensions();
		final String[] blockArgList = blockSizeArg.split(",");
		final int[] dims = Intervals.dimensionsAsIntArray( ImageJFunctions.wrap( image ));

		blockSize = new int[nd];
		int i = 0;
		while (i < blockArgList.length && i < nd) {
			blockSize[i] = Integer.parseInt(blockArgList[i]);
			i++;
		}
		final int N = blockArgList.length - 1;

		while (i < nd) {
			if( blockSize[N] > dims[i] )
				blockSize[i] = dims[i];
			else
				blockSize[i] = blockSize[N];

			i++;
		}
	}

  @SuppressWarnings("unchecked")
  public <T extends RealType<T> & NativeType<T>, M extends N5DatasetMetadata> void process() throws IOException, InterruptedException, ExecutionException {

	if ( metadataStyle.equals(N5Importer.MetadataOmeZarrKey))
	{
	  impMeta = new NgffToImagePlus();
	  writeOmeZarr(1);
	  return;
	}


	final N5Writer n5 = new N5Factory().openWriter(n5RootLocation);
	final Compression compression = getCompression();
	parseBlockSize();

	N5MetadataWriter<M> writer = null;
	if (!metadataStyle.equals(NONE)) {
	  writer = (N5MetadataWriter<M>)styles.get(metadataStyle);
	  if (writer != null)
	  {
		impMeta = impMetaWriterTypes.get(writer.getClass());
	  }
	}

	// check and warn re: RGB image if relevant
	//	if (image.getType() == ImagePlus.COLOR_RGB && !(writer instanceof N5ImagePlusMetadata))
	//	  log.warn("RGB images are best saved using ImageJ metatadata. Other choices "
	//			  + "may lead to unexpected behavior.");


	if (metadataStyle.equals(NONE) ||
			metadataStyle.equals(N5Importer.MetadataImageJKey) ||
			metadataStyle.equals(N5Importer.MetadataCustomKey)) {
	  write(n5, compression, writer);
	} else {
	  writeSplitChannels(n5, compression, writer);
	}
	n5.close();
  }

	@SuppressWarnings({"unchecked", "rawtypes"})
	private <T extends RealType & NativeType, M extends N5DatasetMetadata> void write(
			final N5Writer n5,
			final Compression compression,
			final N5MetadataWriter<M> writer) throws IOException, InterruptedException, ExecutionException {

		if (overwriteChoices.equals(WRITE_SUBSET)) {
			final long[] offset = Arrays.stream(subsetOffset.split(","))
					.mapToLong(Long::parseLong)
					.toArray();

			if (!n5.datasetExists(n5Dataset)) {
				// details don't matter, saveRegions changes this value
				final long[] dimensions = new long[image.getNDimensions()];
				Arrays.fill(dimensions, 1);

				// find data type
				final int type = image.getType();
				DataType n5type;
				switch (type) {
				case ImagePlus.GRAY8:
					n5type = DataType.UINT8;
					break;
				case ImagePlus.GRAY16:
					n5type = DataType.UINT16;
					break;
				case ImagePlus.GRAY32:
					n5type = DataType.FLOAT32;
					break;
				case ImagePlus.COLOR_RGB:
					n5type = DataType.UINT32;
					break;
				default:
					n5type = null;
				}

				final DatasetAttributes attributes = new DatasetAttributes(dimensions, blockSize, n5type, compression);
				n5.createDataset(n5Dataset, attributes);
				writeMetadata(n5, n5Dataset, writer);
			}

			final Img<T> ipImg;
			if (image.getType() == ImagePlus.COLOR_RGB)
				ipImg = (Img<T>)N5IJUtils.wrapRgbAsInt(image);
			else
				ipImg = ImageJFunctions.wrap(image);

			final IntervalView<T> rai = Views.translate(ipImg, offset);
			if (nThreads > 1)
				N5Utils.saveRegion( rai, n5, n5Dataset );
			else {
				final ThreadPoolExecutor threadPool = new ThreadPoolExecutor( nThreads, nThreads, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>());
				progressMonitor( threadPool );
				N5Utils.saveRegion( rai, n5, n5Dataset, threadPool);
				threadPool.shutdown();
			}
		}
		else
		{
			if( overwriteChoices.equals( NO_OVERWRITE ) && n5.datasetExists( n5Dataset ))
			{
				if( ui != null )
					ui.showDialog( String.format("Dataset (%s) already exists, not writing.", n5Dataset ) );
				else
					System.out.println(String.format("Dataset (%s) already exists, not writing.", n5Dataset));

				return;
			}

			// Here, either allowing overwrite, or not allowing, but the dataset
			// does not exist

			// use threadPool even for single threaded execution for progress monitoring
			final ThreadPoolExecutor threadPool = new ThreadPoolExecutor( nThreads, nThreads, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>()	);
			progressMonitor( threadPool );
			N5IJUtils.save( image, n5, n5Dataset, blockSize, compression, threadPool);
			threadPool.shutdown();

			writeMetadata(n5, n5Dataset, writer);
		}
	}

	private  <T extends RealType<T> & NativeType<T> > void writeOmeZarr(
			final int numScales ) throws IOException, InterruptedException, ExecutionException {

		final N5Writer n5 = new N5Factory()
				.gsonBuilder(OmeNgffMetadataParser.gsonBuilder())
				.openWriter(n5RootLocation);

		final Compression compression = getCompression();
		parseBlockSize();

		final N5MetadataWriter<NgffSingleScaleAxesMetadata> writer = new NgffSingleScaleMetadataParser();

		final NgffToImagePlus metaIo = new NgffToImagePlus();
		final NgffSingleScaleAxesMetadata baseMeta = metaIo.readMetadata(image);

		// check and warn re: RGB image if relevant
		// if (image.getType() == ImagePlus.COLOR_RGB && !(writer instanceof
		// N5ImagePlusMetadata))
		// log.warn("RGB images are best saved using ImageJ metatadata. Other
		// choices "
		// + "may lead to unexpected behavior.");
		final Img<T> img = ImageJFunctions.wrap(image);
		write(img, n5, n5Dataset + "/s0", compression, null);

		final DatasetAttributes[] dsetAttrs = new DatasetAttributes[numScales];
		final OmeNgffDataset[] msDatasets = new OmeNgffDataset[numScales];

		String relativePath = String.format("s%d", 0);
		String dset = String.format("%s/%s", n5Dataset, relativePath);
		dsetAttrs[0] = n5.getDatasetAttributes(dset);
		final boolean cOrder = OmeNgffMultiScaleMetadata.cOrder(dsetAttrs[0]);

		final double[] scale = OmeNgffMultiScaleMetadata.reverseIfCorder(dsetAttrs[0], baseMeta.getScale());
		final double[] translation = OmeNgffMultiScaleMetadata.reverseIfCorder(dsetAttrs[0], baseMeta.getTranslation());
		final Axis[] axes = OmeNgffMultiScaleMetadata.reverseIfCorder(dsetAttrs[0], baseMeta.getAxes() );
		final NgffSingleScaleAxesMetadata s0Meta = new NgffSingleScaleAxesMetadata( dset, scale, translation, axes, dsetAttrs[0]);

		msDatasets[0] = new OmeNgffDataset();
		msDatasets[0].path = relativePath;
		msDatasets[0].coordinateTransformations = s0Meta.getCoordinateTransformations();

		try {
			writer.writeMetadata(s0Meta, n5, dset );
		} catch (final Exception e1) { }

		final long[] downsamplingFactors = new long[img.numDimensions()];
		Arrays.fill( downsamplingFactors, 1 );
		for (int i = 1; i < numScales; i++) {

			final long[] factors = MetadataUtils.updateDownsamplingFactors(2, downsamplingFactors, Intervals.dimensionsAsLongArray(img), baseMeta.getAxisTypes());
			final SubsampleIntervalView<T> imgDown = downsample(img, factors);
			relativePath = String.format("s%d", i);
			dset = String.format("%s/%s", n5Dataset, relativePath);

			write(imgDown, n5, dset, compression, null);

			dsetAttrs[i] = n5.getDatasetAttributes(dset);
			final NgffSingleScaleAxesMetadata siMeta = new NgffSingleScaleAxesMetadata( dset,
					OmeNgffMultiScaleMetadata.reverseIfCorder(dsetAttrs[0], MetadataUtils.mul(baseMeta.getScale(), downsamplingFactors)),
					OmeNgffMultiScaleMetadata.reverseIfCorder(dsetAttrs[0], baseMeta.getTranslation()),
					axes,
					dsetAttrs[i]);

			try {
				writer.writeMetadata(siMeta, n5, dset );
			} catch (final Exception e1) { }

			msDatasets[i] = new OmeNgffDataset();
			msDatasets[i].path = relativePath;
			msDatasets[i].coordinateTransformations = siMeta.getCoordinateTransformations();
		}

		final OmeNgffMultiScaleMetadata ms = NgffToImagePlus.buildMetadata( s0Meta, image.getTitle(), n5Dataset, dsetAttrs, msDatasets);
		final OmeNgffMultiScaleMetadata[] msList = new OmeNgffMultiScaleMetadata[]{ms};

		final OmeNgffMetadata meta = new OmeNgffMetadata(n5Dataset, msList);
		try {
			new OmeNgffMetadataParser(cOrder).writeMetadata(meta, n5, n5Dataset);
		} catch (final Exception e) {
			e.printStackTrace();
		}

		n5.close();
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	private <T extends RealType & NativeType, M extends N5DatasetMetadata> void write(
			final RandomAccessibleInterval<T> image,
			final N5Writer n5,
			final String dataset,
			final Compression compression, final N5MetadataWriter<M> writer)
			throws IOException, InterruptedException, ExecutionException {

		if (overwriteChoices.equals(NO_OVERWRITE) && n5.datasetExists(dataset)) {
			if (ui != null)
				ui.showDialog(String.format("Dataset (%s) already exists, not writing.", dataset));
			else
				System.out.println(String.format("Dataset (%s) already exists, not writing.", dataset));

			return;
		}

		// Here, either allowing overwrite, or not allowing, but the dataset does not exist.
		// use threadPool even for single threaded execution for progress monitoring
		final ThreadPoolExecutor threadPool = new ThreadPoolExecutor(nThreads, nThreads, 0L, TimeUnit.MILLISECONDS,
				new LinkedBlockingQueue<Runnable>());
		progressMonitor(threadPool);
		N5Utils.save(image, n5, dataset, blockSize, compression, Executors.newFixedThreadPool(nThreads));
		writeMetadata(n5, dataset, writer);
	}

	private <T extends RealType<T> & NativeType<T>, M extends N5DatasetMetadata> SubsampleIntervalView<T> downsampleSimple(
			final RandomAccessibleInterval<T> img, final int downsampleFactor) {
		return Views.subsample(img, downsampleFactor);
	}

	private <T extends RealType<T> & NativeType<T>, M extends N5DatasetMetadata> SubsampleIntervalView<T> downsample(
			final RandomAccessibleInterval<T> img, final long... downsampleFactors) {
		return Views.subsample(img, downsampleFactors);
	}

	@SuppressWarnings( "unused" )
	private static long[] getOffsetForSaveSubset3d( final ImagePlus imp )
	{
		final int nd = imp.getNDimensions();
		final long[] offset = new long[ nd ];

		offset[ 0 ] = (int)imp.getCalibration().xOrigin;
		offset[ 1 ] = (int)imp.getCalibration().yOrigin;

		int j = 2;
		if( imp.getNSlices() > 1 )
			offset[ j++ ] = (int)imp.getCalibration().zOrigin;

		return offset;
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private <T extends RealType & NativeType, M extends N5Metadata> void writeSplitChannels(
			final N5Writer n5,
			final Compression compression,
			final N5MetadataWriter<M> writer) throws IOException, InterruptedException, ExecutionException
	{
		final Img<T> img;
		if( image.getType() == ImagePlus.COLOR_RGB )
			img = (( Img< T > ) N5IJUtils.wrapRgbAsInt( image ));
		else
			img = ImageJFunctions.wrap(image);

		String datasetString = "";
		int[] blkSz = blockSize;
		for (int c = 0; c < image.getNChannels(); c++) {
			RandomAccessibleInterval<T> channelImg;
			// If there is only one channel, img may be 3d, but we don't want to slice
			// so if we have a 3d image check that the image is multichannel
			if( image.getNChannels() > 1 )
			{
				channelImg = Views.hyperSlice(img, 2, c);

				// if we slice the image, appropriately slice the block size also
				blkSz = sliceBlockSize( 2 );
			} else {
				channelImg = img;
			}

			if (metadataStyle.equals(N5Importer.MetadataN5ViewerKey)) {
				datasetString = String.format("%s/c%d/s0", n5Dataset, c);
			} else if (image.getNChannels() > 1) {
				datasetString = String.format("%s/c%d", n5Dataset, c);
			} else {
				datasetString = n5Dataset;
			}

			if( metadataStyle.equals(N5Importer.MetadataN5ViewerKey) && image.getNFrames() > 1 && image.getNSlices() == 1 )
			{
				// make a 4d image in order XYZT
				channelImg = Views.permute(Views.addDimension(channelImg, 0, 0), 2, 3);
				// expand block size
				blkSz = new int[] { blkSz[0], blkSz[1], 1, blkSz[2] };
			}

			// use threadPool even for single threaded execution for progress monitoring
			final ThreadPoolExecutor threadPool = new ThreadPoolExecutor( nThreads, nThreads, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>()	);
			progressMonitor( threadPool );
			N5Utils.save( channelImg, n5, datasetString, blkSz, compression, threadPool );
			threadPool.shutdown();

			writeMetadata(n5, datasetString, writer);
		}
	}

	private int[] sliceBlockSize( final int exclude )
	{
		final int[] out = new int[ blockSize.length - 1 ];
		int j = 0;
		for( int i = 0; i < blockSize.length; i++ )
			if( i != exclude )
			{
				out[j] = blockSize[i];
				j++;
			}

		return out;
	}

	private <M extends N5Metadata> void writeMetadata(
			final N5Writer n5,
			final String datasetString,
			final N5MetadataWriter<M> writer) {

		if (writer != null) {
			try {
				@SuppressWarnings("unchecked")
				final M meta = (M)impMeta.readMetadata(image);
				writer.writeMetadata(meta, n5, datasetString);
			} catch (final Exception e) {
				e.printStackTrace();
			}
		}
	}

	@Override
	public void run() {

		// add more options
		if (metadataStyle.equals(N5Importer.MetadataCustomKey)) {
			metaSpecDialog = new N5MetadataSpecDialog(this);
			metaSpecDialog.show(MetadataTemplateMapper.RESOLUTION_ONLY_MAPPER);
		} else {
			try {
				process();
			} catch (final IOException e) {
				e.printStackTrace();
			} catch (final InterruptedException e) {
				e.printStackTrace();
			} catch (final ExecutionException e) {
				e.printStackTrace();
			}
		}
	}

	private void progressMonitor( final ThreadPoolExecutor exec )
	{
		new Thread()
		{
			@Override
			public void run()
			{
				IJ.showProgress( 0.01 );
				try
				{
					Thread.sleep( 333 );
					boolean done = false;
					while( !done && !exec.isShutdown() )
					{
						final long i = exec.getCompletedTaskCount();
						final long N = exec.getTaskCount();
						done = i == N;
						IJ.showProgress( (double)i / N );
						Thread.sleep( 333 );
					}
				}
				catch ( final InterruptedException e ) { }
				IJ.showProgress( 1.0 );
			}
		}.start();
		return;
	}

	private Compression getCompression() {

		switch (compressionArg) {
		case GZIP_COMPRESSION:
			return new GzipCompression();
		case LZ4_COMPRESSION:
			return new Lz4Compression();
		case XZ_COMPRESSION:
			return new XzCompression();
		case RAW_COMPRESSION:
			return new RawCompression();
		case BLOSC_COMPRESSION:
			return new BloscCompression();
		default:
			return new RawCompression();
		}
	}

	@Override
	public void windowOpened(final WindowEvent e) {}

	@Override
	public void windowIconified(final WindowEvent e) {}

	@Override
	public void windowDeiconified(final WindowEvent e) {}

	@Override
	public void windowDeactivated(final WindowEvent e) {}

	@Override
	public void windowClosing(final WindowEvent e) {

	  styles.put(N5Importer.MetadataCustomKey, metaSpecDialog.getMapper());
	  impMetaWriterTypes.put(MetadataTemplateMapper.class, new ImagePlusMetadataTemplate());

	  try {
		process();
	  } catch (final IOException e1) {
		e1.printStackTrace();
	  } catch (final InterruptedException e1) {
		e1.printStackTrace();
	  } catch (final ExecutionException e1) {
		e1.printStackTrace();
		}
	}

	@Override
	public void windowClosed(final WindowEvent e) {}

	@Override
	public void windowActivated(final WindowEvent e) {}

}
