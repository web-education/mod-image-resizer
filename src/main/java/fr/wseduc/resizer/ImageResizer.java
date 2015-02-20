/*
 * Copyright © WebServices pour l'Éducation, 2014
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.wseduc.resizer;

import org.imgscalr.Scalr;
import org.vertx.java.busmods.BusModBase;
import org.vertx.java.core.AsyncResult;
import org.vertx.java.core.AsyncResultHandler;
import org.vertx.java.core.Future;
import org.vertx.java.core.Handler;
import org.vertx.java.core.eventbus.Message;
import org.vertx.java.core.json.JsonArray;
import org.vertx.java.core.json.JsonObject;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.imgscalr.Scalr.*;


public class ImageResizer extends BusModBase implements Handler<Message<JsonObject>> {

	private Map<String, FileAccess> fileAccessProviders = new HashMap<>();

	@Override
	public void start(final Future<Void> startedResult) {
		super.start();
		fileAccessProviders.put("file", new FileSystemFileAccess(
				vertx, config.getString("base-path", new File(".").getAbsolutePath())));
		JsonObject gridfs = config.getObject("gridfs");
		if (gridfs != null) {
			String host = gridfs.getString("host", "localhost");
			int port = gridfs.getInteger("port", 27017);
			String dbName = gridfs.getString("db_name");
			String username = gridfs.getString("username");
			String password = gridfs.getString("password");
			int poolSize = gridfs.getInteger("pool_size", 10);
			if (dbName != null) {
				try {
					fileAccessProviders.put("gridfs",
						new GridFsFileAccess(host, port, dbName, username, password, poolSize));
				} catch (UnknownHostException e) {
					logger.error("Invalid mongoDb configuration.", e);
				}
			}
		}
		JsonObject swift = config.getObject("swift");
		if (swift != null) {
			String uri = swift.getString("uri");
			String username = swift.getString("user");
			String password = swift.getString("key");
			if (uri != null && username != null && password != null) {
				try {
					final SwiftAccess swiftAccess = new SwiftAccess(vertx, new URI(uri));
					swiftAccess.init(username, password, new AsyncResultHandler<Void>() {
						@Override
						public void handle(AsyncResult<Void> event) {
							if (event.succeeded()) {
								fileAccessProviders.put("swift", swiftAccess);
							} else {
								logger.error("Swift authentication error", event.cause());
							}
							registerHandler(startedResult);
						}
					});
				} catch (URISyntaxException e) {
					logger.error("Invalid swift uri.", e);
				}
			}
		} else {
			registerHandler(startedResult);
		}
	}

	private void registerHandler(Future<Void> startedResult) {
		eb.registerHandler(config.getString("address", "image.resizer"), this);
		logger.info("BusModBase: Image resizer starts on address: " + config.getString("address"));
		startedResult.setResult(null);
	}

	@Override
	public void stop() {
		super.stop();
		for (FileAccess fa: fileAccessProviders.values()) {
			fa.close();
		}
	}

	@Override
	public void handle(Message<JsonObject> m) {
		switch(m.body().getString("action", "")) {
			case "resize" :
				resize(m);
				break;
			case "crop" :
				crop(m);
				break;
			case "resizeMultiple" :
				resizeMultiple(m);
				break;
			case "compress" :
				compress(m);
				break;
			default :
				sendError(m, "Invalid or missing action");
		}
	}

	private void compress(final Message<JsonObject> m) {
		final Number quality = m.body().getNumber("quality");
		if (quality == null || quality.floatValue() > 1f || quality.floatValue() <= 0f) {
			sendError(m, "Invalid quality.");
			return;
		}
		final FileAccess fSrc = getFileAccess(m, m.body().getString("src"));
		if (fSrc == null) {
			return;
		}
		final FileAccess fDest = getFileAccess(m, m.body().getString("dest"));
		if (fDest == null) {
			return;
		}
		fSrc.read(m.body().getString("src"), new Handler<ImageFile>() {
			@Override
			public void handle(ImageFile src) {
				if (src == null) {
					sendError(m, "Input file not found.");
					return;
				}
				try {
					BufferedImage srcImg = ImageIO.read(src.getInputStream());
					persistImage(src, srcImg, srcImg, fDest, quality.floatValue(), m);
				} catch (IOException e) {
					logger.error("Error processing image.", e);
					sendError(m, "Error processing image.", e);
				}
			}
		});
	}

	private void crop(final Message<JsonObject> m) {
		final Integer width = m.body().getInteger("width");
		final Integer height = m.body().getInteger("height");
		final Integer x = m.body().getInteger("x", 0);
		final Integer y = m.body().getInteger("y", 0);
		Number n = m.body().getNumber("quality");
		final float quality = (n != null) ? n.floatValue() : 0.8f;
		if (width == null || height == null) {
			sendError(m, "Invalid size.");
			return;
		}
		final FileAccess fSrc = getFileAccess(m, m.body().getString("src"));
		if (fSrc == null) {
			return;
		}
		final FileAccess fDest = getFileAccess(m, m.body().getString("dest"));
		if (fDest == null) {
			return;
		}
		fSrc.read(m.body().getString("src"), new Handler<ImageFile>() {
			@Override
			public void handle(ImageFile src) {
				if (src == null) {
					sendError(m, "Input file not found.");
					return;
				}
				try {
					BufferedImage srcImg = ImageIO.read(src.getInputStream());
					if (srcImg.getWidth() < (x + width) || srcImg.getHeight() < (y + height)) {
						sendError(m, "Source image too small for crop.");
						return;
					}
					BufferedImage cropped = Scalr.crop(srcImg, x, y, width, height);
					persistImage(src, srcImg, cropped, fDest, quality, m);
				} catch (IOException e) {
					logger.error("Error processing image.", e);
					sendError(m, "Error processing image.", e);
				}
			}
		});
	}

	private void resize(final Message<JsonObject> m) {
		final Integer width = m.body().getInteger("width");
		final Integer height = m.body().getInteger("height");
		final boolean stretch = m.body().getBoolean("stretch", false);
		Number n = m.body().getNumber("quality");
		final float quality = (n != null) ? n.floatValue() : 0.8f;
		if (width == null && height == null) {
			sendError(m, "Invalid size.");
			return;
		}
		final FileAccess fSrc = getFileAccess(m, m.body().getString("src"));
		if (fSrc == null) {
			return;
		}
		final FileAccess fDest = getFileAccess(m, m.body().getString("dest"));
		if (fDest == null) {
			return;
		}
		fSrc.read(m.body().getString("src"), new Handler<ImageFile>() {
			@Override
			public void handle(ImageFile src) {
				if (src == null) {
					sendError(m, "Input file not found.");
					return;
				}
				try {
					BufferedImage srcImg = ImageIO.read(src.getInputStream());
					BufferedImage resized = doResize(width, height, stretch, srcImg);
					persistImage(src, srcImg, resized, fDest, quality, m);
				} catch (IOException e) {
					logger.error("Error processing image.", e);
					sendError(m, "Error processing image.", e);
				}
			}
		});
	}

	private void resizeMultiple(final Message<JsonObject> m) {
		final JsonArray destinations = m.body().getArray("destinations");
		Number n = m.body().getNumber("quality");
		final float quality = (n != null) ? n.floatValue() : 0.8f;
		if (destinations == null || destinations.size() == 0) {
			sendError(m, "Invalid outputs files.");
			return;
		}
		final FileAccess fSrc = getFileAccess(m, m.body().getString("src"));
		if (fSrc == null) {
			return;
		}
		fSrc.read(m.body().getString("src"), new Handler<ImageFile>() {
			@Override
			public void handle(ImageFile src) {
				if (src == null) {
					sendError(m, "Input file not found.");
					return;
				}
				final AtomicInteger count = new AtomicInteger(destinations.size());
				final JsonObject results = new JsonObject();
				BufferedImage srcImg;
				try {
					srcImg = ImageIO.read(src.getInputStream());
				} catch (IOException e) {
					logger.error("Error processing image.", e);
					sendError(m, "Error processing image.", e);
					return;
				}
				for (Object o: destinations) {
					if (!(o instanceof JsonObject)) {
						checkReply(m, count, results);
						continue;
					}
					final JsonObject output = (JsonObject) o;
					final Integer width = output.getInteger("width");
					final Integer height = output.getInteger("height");
					final boolean stretch = output.getBoolean("stretch", false);
					final FileAccess fDest = getFileAccess(m, output.getString("dest"));
					if (fDest == null || (width == null && height == null)) {
						checkReply(m, count, results);
						continue;
					}
					try {
						BufferedImage resized = doResize(width, height, stretch, srcImg);
						persistImage(src, srcImg, resized, fDest, output.getString("dest"), quality,
								new Handler<String>() {
							@Override
							public void handle(String event) {
								if (event != null && !event.trim().isEmpty()) {
									results.putString(output.getInteger("width", 0) + "x" +
											output.getInteger("height", 0), event);
								}
								checkReply(m, count, results);
							}
						});
					} catch (IOException e) {
						logger.error("Error processing image.", e);
					}
				}
			}

			private void checkReply(Message<JsonObject> m, AtomicInteger count, JsonObject results) {
				final int c = count.decrementAndGet();
				if (c == 0 && results != null && results.size() > 0) {
					sendOK(m,  new JsonObject().putObject("outputs", results));
				} else if (c == 0) {
					sendError(m, "Unable to resize image.");
				}
			}
		});
	}

	private BufferedImage doResize(Integer width, Integer height, boolean stretch,
			BufferedImage srcImg) {
		BufferedImage resized;
		if (width != null && height != null && !stretch) {
			if (srcImg.getHeight()/(float)height < srcImg.getWidth()/(float)width) {
				resized = Scalr.resize(srcImg, Method.ULTRA_QUALITY,
						Mode.FIT_TO_HEIGHT, width, height);
			} else {
				resized = Scalr.resize(srcImg, Method.ULTRA_QUALITY,
						Mode.FIT_TO_WIDTH, width, height);
			}
			resized.flush();
			int x = (resized.getWidth() - width) / 2;
			int y = (resized.getHeight() - height) / 2;
			resized = Scalr.crop(resized, x, y, width, height);
		} else if (width != null && height != null) {
			resized = Scalr.resize(srcImg, Method.ULTRA_QUALITY,
					Mode.FIT_EXACT, width, height);
		} else if (height != null) {
			resized = Scalr.resize(srcImg, Method.ULTRA_QUALITY,
					Mode.FIT_TO_HEIGHT, height);
		} else {
			resized = Scalr.resize(srcImg, Method.ULTRA_QUALITY,
					Mode.FIT_TO_WIDTH, width);
		}
		return resized;
	}

	private void persistImage(ImageFile src, BufferedImage srcImg, BufferedImage resized,
							  FileAccess fDest, final Message<JsonObject> m) throws IOException {
		persistImage(src, srcImg, resized, fDest, 0.8f, m);
	}

	private void persistImage(ImageFile src, BufferedImage srcImg, BufferedImage resized,
			FileAccess fDest, float quality, final Message<JsonObject> m) throws IOException {
		srcImg.flush();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		if (logger.isDebugEnabled()) {
			logger.debug("Original file name : " + src.getFilename());
			logger.debug("Original file extension : " + getExtension(src.getFilename()));
		}
		Iterator<ImageWriter> writers =  ImageIO.getImageWritersByFormatName(getExtension(src.getFilename()));
		if (!writers.hasNext()) {
			writers =  ImageIO.getImageWritersByFormatName("jpg");
		}
		ImageWriter writer = writers.next();

		ImageOutputStream ios = ImageIO.createImageOutputStream(out);
		writer.setOutput(ios);

		ImageWriteParam param = writer.getDefaultWriteParam();
		if (param.canWriteCompressed()) {
			param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			param.setCompressionQuality(quality);
		}
		writer.write(null, new IIOImage(resized, null, null), param);
		resized.flush();
		ImageFile outImg = new ImageFile(out.toByteArray(), src.getFilename(),
				src.getContentType());
		ios.close();
		final int size = out.size();
		out.close();
		fDest.write(m.body().getString("dest"), outImg, new Handler<String>() {
			@Override
			public void handle(String result) {
				if (result != null && !result.trim().isEmpty()) {
					sendOK(m, new JsonObject().putString("output", result).putNumber("size", size));
				} else {
					sendError(m, "Error writing file.");
				}
			}
		});
	}

	private void persistImage(ImageFile src, BufferedImage srcImg, BufferedImage resized,
			FileAccess fDest, String destination, Handler<String> handler) throws IOException {
		persistImage(src, srcImg, resized, fDest, destination, 0.8f, handler);
	}

	private void persistImage(ImageFile src, BufferedImage srcImg, BufferedImage resized,
			FileAccess fDest, String destination, float quality, Handler<String> handler) throws IOException {
		srcImg.flush();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		if (logger.isDebugEnabled()) {
			logger.debug("Original file name : " + src.getFilename());
			logger.debug("Original file extension : " + getExtension(src.getFilename()));
			logger.debug("Destination : " + destination);
		}
		Iterator<ImageWriter> writers =  ImageIO.getImageWritersByFormatName(getExtension(src.getFilename()));
		if (!writers.hasNext()) {
			writers =  ImageIO.getImageWritersByFormatName("jpg");
		}
		ImageWriter writer = writers.next();

		ImageOutputStream ios = ImageIO.createImageOutputStream(out);
		writer.setOutput(ios);

		ImageWriteParam param = writer.getDefaultWriteParam();
		if (param.canWriteCompressed()) {
			param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			param.setCompressionQuality(quality);
		}
		writer.write(null, new IIOImage(resized, null, null), param);
		resized.flush();
		ImageFile outImg = new ImageFile(out.toByteArray(), src.getFilename(),
				src.getContentType());
		ios.close();
		out.close();
		fDest.write(destination, outImg, handler);
	}

	private FileAccess getFileAccess(Message<JsonObject> m, String path) {
		if (path == null || !path.contains("://")) {
			sendError(m, "Invalid path : " + path);
			return null;
		}
		String protocol = path.substring(0, path.indexOf("://"));
		FileAccess fa = fileAccessProviders.get(protocol);
		if (fa == null) {
			sendError(m, "Invalid file protocol : " + protocol);
			return null;
		}
		return fa;
	}

	private String getExtension(String fileName) {
		if (fileName != null) {
			int idx = fileName.lastIndexOf('.');
			if (idx > 0 && fileName.length() > idx + 1) {
				return fileName.substring(idx + 1);
			}
		}
		return "";
	}

}
