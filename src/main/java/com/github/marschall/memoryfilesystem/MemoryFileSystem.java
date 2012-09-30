package com.github.marschall.memoryfilesystem;

import static com.github.marschall.memoryfilesystem.MemoryFileSystemProperties.BASIC_FILE_ATTRIBUTE_VIEW_NAME;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessMode;
import java.nio.file.DirectoryStream;
import java.nio.file.DirectoryStream.Filter;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardOpenOption;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.annotation.PreDestroy;

class MemoryFileSystem extends FileSystem {

  private final String separator;

  private final MemoryFileSystemProvider provider;

  private final MemoryFileStore store;

  private final Iterable<FileStore> stores;

  private final ClosedFileSystemChecker checker;

  private volatile Map<Root, MemoryDirectory> roots;
  
  private volatile Path defaultPath;

  private final MemoryUserPrincipalLookupService userPrincipalLookupService;

  private final PathParser pathParser;

  private final EmptyPath emptyPath;

  MemoryFileSystem(String separator, PathParser pathParser, MemoryFileSystemProvider provider, MemoryFileStore store,
      MemoryUserPrincipalLookupService userPrincipalLookupService, ClosedFileSystemChecker checker) {
    this.separator = separator;
    this.pathParser = pathParser;
    this.provider = provider;
    this.store = store;
    this.userPrincipalLookupService = userPrincipalLookupService;
    this.checker = checker;
    this.stores = Collections.<FileStore>singletonList(store);
    this.emptyPath = new EmptyPath(this);
  }

  EmptyPath getEmptyPath() {
    return this.emptyPath;
  }


  /**
   * Sets the root directories.
   * 
   * <p>This is a bit annoying.</p>
   * 
   * @param rootDirectories the root directories, not {@code null},
   *  should not be modified, no defensive copy will be made
   */
  void setRootDirectories(Map<Root, MemoryDirectory> rootDirectories) {
    this.roots = rootDirectories;
  }
  
  /**
   * Sets the current working directory.
   * 
   * <p>This is used to resolve relative paths. This has to be set
   * after {@link #setRootDirectories(Map)}</p>.
   * 
   * @param currentWorkingDirectory the default directory path
   */
  void setCurrentWorkingDirectory(String currentWorkingDirectory) {
    this.defaultPath = this.getPath(currentWorkingDirectory);
    if (!this.defaultPath.isAbsolute()) {
      throw new IllegalArgumentException("current working directory must be absolute");
    }
  }
  
  Path getDefaultPath() {
    return this.defaultPath;
  }


  SeekableByteChannel newByteChannel(AbstractPath path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
    // TODO check options
    // TODO check attributes
    this.checker.check();
    boolean isAppend = options.contains(StandardOpenOption.APPEND);
    MemoryDirectory directory = this.getRootDirectory(path);
    
    throw new UnsupportedOperationException();
  }
  
  DirectoryStream<Path> newDirectoryStream(AbstractPath abstractPath, Filter<? super Path> filter) {
    // TODO Auto-generated method stub
    throw new UnsupportedOperationException();
  }


  void createDirectory(AbstractPath path, FileAttribute<?>... attrs) throws IOException {
    this.checker.check();
    MemoryDirectory rootDirectory = this.getRootDirectory(path);
    
    Path absolutePath = path.toAbsolutePath();
    if (!(absolutePath instanceof ElementPath)) {
      throw new IOException("can not be created");
    }
    final ElementPath absoluteElementPath = (ElementPath) absolutePath;
    final Path parent = absolutePath.getParent();
    
    this.withWriteLockOnLastDo(rootDirectory, (AbstractPath) parent, new MemoryEntryBlock<Void>() {
      
      @Override
      public Void value(MemoryEntry entry) throws IOException {
        if (!(entry instanceof MemoryDirectory)) {
          throw new IOException(parent + " is not a directory");
        }
        MemoryDirectory newDirectory = new MemoryDirectory();
        String name = absoluteElementPath.getLastNameElement();
        ((MemoryDirectory) entry).addEntry(name, newDirectory);
        return null;
      }
    });
  }
  

  void checkAccess(AbstractPath path, final AccessMode... modes) throws IOException {
    this.checker.check();
    this.accessFile(path, new MemoryEntryBlock<Void>() {

      @Override
      public Void value(MemoryEntry entry) throws IOException {
        entry.checkAccess(modes);
        return null;
      }
    });
  }
  
  public <A extends BasicFileAttributes> A readAttributes(AbstractPath path, final Class<A> type, LinkOption... options) throws IOException {
    this.checker.check();
    return this.accessFile(path, new MemoryEntryBlock<A>() {

      @Override
      public A value(MemoryEntry entry) throws IOException {
        return entry.readAttributes(type);
      }
    });
  }
  
  private <R> R accessFile(AbstractPath path, MemoryEntryBlock<? extends R> callback) throws IOException {
    this.checker.check();
    MemoryDirectory directory = this.getRootDirectory(path);
    Path absolutePath = path.toAbsolutePath();
    return this.withReadLockDo(directory, (AbstractPath) absolutePath, callback);
  }
  

  interface MemoryEntryBlock<R> {
    
    R value(MemoryEntry entry) throws IOException;
    
  }
  
  private void withWriteLockOnLastDo(MemoryDirectory root, AbstractPath path, MemoryEntryBlock callback) throws IOException {
    if (path instanceof Root) {
      try (AutoRelease lock = root.writeLock()) {
        callback.value(root);
      }
    } else if (path instanceof ElementPath) {
      ElementPath elementPath = (ElementPath) path;
      try (AutoRelease lock = root.readLock()) {
        withWriteLockOnLastDo(root, elementPath, 0, path.getNameCount(), callback);
      }
    } else {
      throw new IllegalArgumentException("unknown path type" + path);
    }
    
  }

  private void withWriteLockOnLastDo(MemoryEntry parent, ElementPath path, int i, int length, MemoryEntryBlock callback) throws IOException {
    if (!(parent instanceof MemoryDirectory)) {
      //TODO construct better error message
      throw new IOException("not a directory");
    }
    
    MemoryEntry entry = ((MemoryDirectory) parent).getEntry(path.getNameElement(i));
    if (entry == null) {
      //TODO construct better error message
      throw new NoSuchFileException("directory does not exist");
    }
    
    if (i == length - 1) {
      try (AutoRelease lock = entry.writeLock()) {
        callback.value(entry);
      }
    } else {
      try (AutoRelease lock = entry.readLock()) {
        this.withWriteLockOnLastDo(entry, path, i + 1, length, callback);
      }
    }
  }
  

  private <R> R withReadLockDo(MemoryDirectory root, AbstractPath path, MemoryEntryBlock<? extends R> callback) throws IOException {
    if (path instanceof Root) {
      try (AutoRelease lock = root.readLock()) {
        return callback.value(root);
      }
    } else if (path instanceof ElementPath) {
      ElementPath elementPath = (ElementPath) path;
      try (AutoRelease lock = root.readLock()) {
        return withReadLockDo(root, elementPath, 0, path.getNameCount(), callback);
      }
    } else {
      throw new IllegalArgumentException("unknown path type" + path);
    }
  }

  private <R> R withReadLockDo(MemoryEntry parent, ElementPath path, int i, int length, MemoryEntryBlock<? extends R> callback) throws IOException {
    if (!(parent instanceof MemoryDirectory)) {
      //TODO construct better error message
      throw new IOException("not a directory");
    }
    
    MemoryEntry entry = ((MemoryDirectory) parent).getEntry(path.getNameElement(i));
    if (entry == null) {
      //TODO construct better error message
      throw new NoSuchFileException("directory does not exist");
    }
    
    if (i == length - 1) {
      try (AutoRelease lock = entry.readLock()) {
        return callback.value(entry);
      }
    } else {
      try (AutoRelease lock = entry.readLock()) {
        return this.withReadLockDo(entry, path, i + 1, length, callback);
      }
    }
  }
  
  private MemoryDirectory getRootDirectory(AbstractPath path) throws IOException {
    MemoryDirectory directory = this.roots.get(path.getRoot());
    if (directory == null) {
      throw new IOException("the root of " + path + " does not exist");
    }
    return directory;
  }



  void delete(AbstractPath abstractPath, Path path) {
    // TODO Auto-generated method stub
    throw new UnsupportedOperationException();
  }


  @Override
  public FileSystemProvider provider() {
    this.checker.check();
    return this.provider;
  }


  @Override
  @PreDestroy // closing twice is explicitly allowed by the contract
  public void close() throws IOException {
    this.checker.close();
    this.provider.close(this);
  }


  @Override
  public boolean isOpen() {
    return this.checker.isOpen();
  }


  @Override
  public boolean isReadOnly() {
    this.checker.check();
    return this.store.isReadOnly();
  }


  @Override
  public String getSeparator() {
    this.checker.check();
    return this.separator;
  }


  @Override
  public Iterable<Path> getRootDirectories() {
    this.checker.check();
    // this is fine because the iterator does not support modification
    return (Iterable<Path>) ((Object) this.roots.keySet());
  }


  @Override
  public Iterable<FileStore> getFileStores() {
    this.checker.check();
    return this.stores;
  }


  @Override
  public Set<String> supportedFileAttributeViews() {
    this.checker.check();
    return Collections.singleton(BASIC_FILE_ATTRIBUTE_VIEW_NAME);
  }


  @Override
  public Path getPath(String first, String... more) {
    this.checker.check();
    // TODO check for maximum length
    // TODO check for valid characters
    return this.pathParser.parse(this.roots.keySet(), first, more);
  }
  

  @Override
  public PathMatcher getPathMatcher(String syntaxAndPattern) {
    this.checker.check();
    int colonIndex = syntaxAndPattern.indexOf(":");
    if (colonIndex <= 0 || colonIndex == syntaxAndPattern.length() - 1) {
      throw new IllegalArgumentException("syntaxAndPattern must have form \"syntax:pattern\" but was \"" + syntaxAndPattern + "\"");
    }
    
    String syntax = syntaxAndPattern.substring(0, colonIndex);
    String pattern = syntaxAndPattern.substring(colonIndex + 1);
    if (syntax.equalsIgnoreCase(GlobPathMatcher.name())) {
      return new GlobPathMatcher(pattern);
    }
    if (syntax.equalsIgnoreCase(RegexPathMatcher.name())) {
      Pattern regex = Pattern.compile(pattern);
      return new RegexPathMatcher(regex);
    }
    
    throw new UnsupportedOperationException("unsupported syntax \"" + syntax + "\"");
  }


  @Override
  public UserPrincipalLookupService getUserPrincipalLookupService() {
    this.checker.check();
    return this.userPrincipalLookupService;
  }


  @Override
  public WatchService newWatchService() throws IOException {
    this.checker.check();
    // TODO Auto-generated method stub
    // TODO make configurable
    throw new UnsupportedOperationException();
  }

  String getKey() {
    return this.store.getKey();
  }


  FileStore getFileStore() {
    return this.store;
  }

}
