package com.google.devtools.build.lib.sandbox;

import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.GZIPInputStream;


/**
 * Helper class to help manage custom rootfs images for the sandbox.
 */
public class LinuxSandboxRootfsManager {
  private FileSystem fs;
  private final String imagesRoot;
  private Reporter reporter;

  // files that should be copied from host machine into rootfs
  private static final String COPY_FROM_HOST[] = new String[]{
    "/etc/hosts",
    "/etc/resolv.conf",
  };

  public static final Set<String> MOUNT_BLACKLIST = new HashSet<String>(
    Arrays.asList(new String[]{
    "dev",
    "home",
    "mnt",
    "proc",
    "root",
    "srv",
    "sys",
    "tmp",
  }));

  public LinuxSandboxRootfsManager(FileSystem fs, String imagesRoot, Reporter reporter) {
    this.fs = fs;
    this.imagesRoot = imagesRoot;
    this.reporter = reporter;
  }

  public interface LazyInputStream {
    public InputStream getStream() throws IOException;
  }

  public String getRootfsPath(URL url) throws IOException {
    return this.getRootfsPath(url.toString(), ()-> url.openStream());
  }

  public String getRootfsPath(Path archivePath) throws IOException {
    return this.getRootfsPath(archivePath.getPathString(), ()-> new FileInputStream(archivePath.getPathString()));
  }

  public synchronized String getRootfsPath(String rootfsName, LazyInputStream lazyStream) throws IOException {
    Path basePath = fs.getPath(this.imagesRoot).getRelative(DigestUtils.sha256Hex(rootfsName));
    String basePathString = basePath.getPathString();
    if (basePath.exists()) {
      return basePathString;
    }
    InputStream stream = null;
    TarArchiveInputStream tarStream = null;
    reporter.handle(Event.info("Creating new rootfs image for " + rootfsName));
    try {
      stream = lazyStream.getStream();
      tarStream = new TarArchiveInputStream(new GZIPInputStream(stream));
      TarArchiveEntry tarEntry;
      while ((tarEntry = tarStream.getNextTarEntry()) != null) {
        this.extractTarEntry(basePath, tarStream, tarEntry);
      }

      for (String path : COPY_FROM_HOST) {
        File systemFile = new File(path);
        if (!systemFile.exists()) {
          continue;
        }
        String rootfsPath = basePathString + path;
        File rootfsFile = new File(rootfsPath);
        Files.copy(systemFile.toPath(), rootfsFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
      }

      return basePathString;
    } catch(Exception e) {
      if (basePath.exists()) {
        FileSystemUtils.deleteTree(basePath);
      }
      throw e;
    } finally {
      if (tarStream != null) {
        tarStream.close();
      }
      if (stream != null) {
        stream.close();
      }
    }
  }

  private void extractTarEntry(Path basePath, TarArchiveInputStream tarStream, TarArchiveEntry tarEntry) throws IOException {
    String name = tarEntry.getName();
    for (String prefix: MOUNT_BLACKLIST) {
      if (name.startsWith(prefix)) {
        return;
      }
    }
    Path filename = basePath.getRelative(name);
    FileSystemUtils.createDirectoryAndParents(filename.getParentDirectory());
    if (tarEntry.isDirectory()) {
      FileSystemUtils.createDirectoryAndParents(filename);
    } else {
      if (tarEntry.isSymbolicLink()) {
        PathFragment linkName = new PathFragment(tarEntry.getLinkName());
        try {
          FileSystemUtils.ensureSymbolicLink(filename, linkName);
        } catch (IOException e) {
          // TODO(naphat) this is most likely unicode file name issue, but the exception is way too broad
        }
      } else {
        try {
          Files.copy(
            tarStream, filename.getPathFile().toPath(), StandardCopyOption.REPLACE_EXISTING);
          filename.chmod(tarEntry.getMode());
        } catch (InvalidPathException e) {
          // TODO(naphat) this is most likely unicode file name issue
        }
      }
    }
  }
}
