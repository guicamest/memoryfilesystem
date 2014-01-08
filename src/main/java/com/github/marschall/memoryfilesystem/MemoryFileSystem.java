package com.github.marschall.memoryfilesystem;

import static com.github.marschall.memoryfilesystem.AutoReleaseLock.autoRelease;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.DirectoryStream.Filter;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystemLoopException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardOpenOption;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileOwnerAttributeView;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Pattern;

import javax.annotation.PreDestroy;

class MemoryFileSystem extends FileSystem {

  private static final Set<String> UNSUPPORTED_INITIAL_ATTRIBUES;

  private final String key;

  private final String separator;

  private final MemoryFileSystemProvider provider;

  private final MemoryFileStore store;

  private final Iterable<FileStore> stores;

  private final ClosedFileSystemChecker checker;

  private volatile Map<Root, MemoryDirectory> roots;

  private volatile Map<String, Root> rootByKey;

  private volatile AbstractPath defaultPath;

  private final MemoryUserPrincipalLookupService userPrincipalLookupService;

  private final PathParser pathParser;

  private final EmptyPath emptyPath;

  // computes the file name to be stored of a file
  private final StringTransformer storeTransformer;

  // computes the look up key of a file name
  private final StringTransformer lookUpTransformer;

  private final Collator collator;

  private final Set<Class<? extends FileAttributeView>> additionalViews;

  private final Set<String> supportedFileAttributeViews;

  /**
   * Operations involving multiple paths (copy and move) need to use
   * lock ordering to avoid deadlocks. During the lock acquisition phase
   * no changes must be made that might affect the ordering. This involves
   * deleting and creating files (with a different capitalization) or links.
   * We assume deleting is less commong so we block this operation.
   */
  private final ReadWriteLock pathOrderingLock;

  private final Set<PosixFilePermission> umask;

  static {
    Set<String> unsupported = new HashSet<>(3);
    unsupported.add("lastAccessTime");
    unsupported.add("creationTime");
    unsupported.add("lastModifiedTime");
    UNSUPPORTED_INITIAL_ATTRIBUES = Collections.unmodifiableSet(unsupported);
  }

  MemoryFileSystem(String key, String separator, PathParser pathParser, MemoryFileSystemProvider provider, MemoryFileStore store,
          MemoryUserPrincipalLookupService userPrincipalLookupService, ClosedFileSystemChecker checker, StringTransformer storeTransformer,
          StringTransformer lookUpTransformer, Collator collator, Set<Class<? extends FileAttributeView>> additionalViews,
          Set<PosixFilePermission> umask) {
    this.key = key;
    this.separator = separator;
    this.pathParser = pathParser;
    this.provider = provider;
    this.store = store;
    this.userPrincipalLookupService = userPrincipalLookupService;
    this.checker = checker;
    this.storeTransformer = storeTransformer;
    this.lookUpTransformer = lookUpTransformer;
    this.collator = collator;
    this.additionalViews = additionalViews;
    this.umask = umask;
    this.stores = Collections.<FileStore>singletonList(store);
    this.emptyPath = new EmptyPath(this);
    this.supportedFileAttributeViews = this.buildSupportedFileAttributeViews(additionalViews);
    this.pathOrderingLock = new ReentrantReadWriteLock();
  }

  private Set<String> buildSupportedFileAttributeViews(Set<Class<? extends FileAttributeView>> additionalViews) {
    if (additionalViews.isEmpty()) {
      return Collections.singleton(FileAttributeViews.BASIC);
    } else {
      Set<String> views = new HashSet<>(additionalViews.size() + 2);
      views.add(FileAttributeViews.BASIC);
      for (Class<? extends FileAttributeView> viewClass : additionalViews) {
        if (FileOwnerAttributeView.class.isAssignableFrom(viewClass)) {
          views.add(FileAttributeViews.OWNER);
        }
        views.add(FileAttributeViews.mapAttributeView(viewClass));
      }
      return Collections.unmodifiableSet(views);
    }
  }

  String getKey() {
    return this.key;
  }

  Set<PosixFilePermission> getUmask() {
    return this.umask;
  }

  EntryCreationContext newEntryCreationContext() throws IOException {
    UserPrincipal user = this.getCurrentUser();
    GroupPrincipal group = this.getGroupOf(user);
    return new EntryCreationContext(this.additionalViews, this.umask, user, group);
  }

  private UserPrincipal getCurrentUser() {
    UserPrincipal currentUser = CurrentUser.get();
    if (currentUser != null) {
      return currentUser;
    } else {
      return this.userPrincipalLookupService.getDefaultUser();
    }
  }

  private GroupPrincipal getGroupOf(UserPrincipal user) throws IOException {
    return this.userPrincipalLookupService.lookupPrincipalByGroupName(user.getName());
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
    this.rootByKey = this.buildRootsByKey(rootDirectories.keySet());
  }

  private Map<String, Root> buildRootsByKey(Collection<Root> rootDirectories) {
    if (rootDirectories.isEmpty()) {
      throw new IllegalArgumentException("a file system root must be present");
    } else if (rootDirectories.size() == 1) {
      Root root = rootDirectories.iterator().next();
      String key = this.lookUpTransformer.transform(root.getKey());
      return Collections.singletonMap(key, root);
    } else {
      Map<String, Root> map = new HashMap<>(rootDirectories.size());
      for (Root root : rootDirectories) {
        String key = this.lookUpTransformer.transform(root.getKey());
        map.put(key, root);
      }
      return map;
    }
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

  AbstractPath getDefaultPath() {
    return this.defaultPath;
  }


  BlockChannel newFileChannel(AbstractPath path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
    this.checker.check();
    MemoryFile file = this.getFile(path, options, attrs);
    return file.newChannel(options, path);
  }


  InputStream newInputStream(AbstractPath path, OpenOption... options) throws IOException {
    this.checker.check();
    Set<OpenOption> optionsSet;
    if (options == null || options.length == 0) {
      optionsSet = Collections.emptySet();
    } else {
      optionsSet = new HashSet<>(options.length);
      for (OpenOption option : options) {
        optionsSet.add(option);
      }
    }
    MemoryFile file = this.getFile(path, optionsSet);
    return file.newInputStream(optionsSet, path);
  }


  OutputStream newOutputStream(AbstractPath path, OpenOption... options) throws IOException {
    this.checker.check();
    Set<OpenOption> optionsSet;
    if (options == null || options.length == 0) {
      optionsSet = Collections.emptySet();
    } else {
      optionsSet = new HashSet<>(options.length);
      for (OpenOption option : options) {
        optionsSet.add(option);
      }
    }
    MemoryFile file = this.getFile(path, optionsSet);
    return file.newOutputStream(optionsSet, path);
  }

  private static void checkSupportedInitialAttributes(FileAttribute<?>... attrs) {
    if (attrs != null) {
      for (FileAttribute<?> attribute : attrs) {
        String attributeName = attribute.name();
        if (UNSUPPORTED_INITIAL_ATTRIBUES.contains(attributeName)) {
          throw new UnsupportedOperationException("'" + attributeName + "' not supported as initial attribute");
        }
      }
    }
  }

  private MemoryFile getFile(final AbstractPath path, final Set<? extends OpenOption> options, final FileAttribute<?>... attrs) throws IOException {
    AbstractPath absolutePath = (AbstractPath) path.toAbsolutePath().normalize();
    if (absolutePath.isRoot()) {
      throw new FileSystemException(path.toString(), null, "is not a file");
    }
    final ElementPath elementPath = (ElementPath) absolutePath;
    MemoryDirectory rootDirectory = this.getRootDirectory(absolutePath);

    return this.withWriteLockOnLastDo(rootDirectory, (AbstractPath) absolutePath.getParent(), Options.isFollowSymLinks(options), new MemoryDirectoryBlock<MemoryFile>() {

      @Override
      public MemoryFile value(MemoryDirectory directory) throws IOException {
        boolean isCreateNew = options.contains(StandardOpenOption.CREATE_NEW);
        String fileName = elementPath.getLastNameElement();
        String key = MemoryFileSystem.this.lookUpTransformer.transform(fileName);
        if (isCreateNew) {
          String name = MemoryFileSystem.this.storeTransformer.transform(fileName);
          MemoryFile file = new MemoryFile(name, MemoryFileSystem.this.newEntryCreationContext());
          checkSupportedInitialAttributes(attrs);
          AttributeAccessors.setAttributes(file, attrs);
          // will throw an exception if already present
          directory.addEntry(key, file);
          return file;
        } else {
          MemoryEntry storedEntry = directory.getEntry(key);
          if (storedEntry == null) {
            boolean isCreate = options.contains(StandardOpenOption.CREATE);
            if (isCreate) {
              String name = MemoryFileSystem.this.storeTransformer.transform(fileName);
              MemoryFile file = new MemoryFile(name, MemoryFileSystem.this.newEntryCreationContext());
              checkSupportedInitialAttributes(attrs);
              AttributeAccessors.setAttributes(file, attrs);
              directory.addEntry(key, file);
              return file;
            } else {
              throw new NoSuchFileException(path.toString());
            }
          }
          if (storedEntry instanceof MemoryFile) {
            return (MemoryFile) storedEntry;
          } else {
            throw new IOException("file is a directory");
          }

        }
      }
    });

  }

  DirectoryStream<Path> newDirectoryStream(final AbstractPath abstractPath, final Filter<? super Path> filter) throws IOException {
    final AbstractPath absolutePath = (AbstractPath) abstractPath.toAbsolutePath().normalize();
    MemoryDirectory root = this.getRootDirectory(absolutePath);
    return this.withReadLockDo(root, abstractPath, false, new MemoryEntryBlock<DirectoryStream<Path>>() {

      @Override
      public DirectoryStream<Path> value(MemoryEntry entry) throws IOException {
        if (!(entry instanceof MemoryDirectory)) {
          throw new NotDirectoryException(abstractPath.toString());
        }
        MemoryDirectory directory = (MemoryDirectory) entry;
        return directory.newDirectoryStream(absolutePath, filter);
      }
    });
  }


  void createDirectory(AbstractPath path, final FileAttribute<?>... attrs) throws IOException {
    this.createFile(path, new MemoryEntryCreator() {

      @Override
      public MemoryEntry create(String name) throws IOException {
        MemoryDirectory directory = new MemoryDirectory(name, MemoryFileSystem.this.newEntryCreationContext());
        AttributeAccessors.setAttributes(directory, attrs);
        return directory;
      }

    });
  }

  void createSymbolicLink(AbstractPath link, final AbstractPath target, final FileAttribute<?>... attrs) throws IOException {
    this.createFile(link, new MemoryEntryCreator() {

      @Override
      public MemoryEntry create(String name) throws IOException {
        MemorySymbolicLink symbolicLink = new MemorySymbolicLink(name, target, MemoryFileSystem.this.newEntryCreationContext());
        AttributeAccessors.setAttributes(symbolicLink, attrs);
        return symbolicLink;
      }

    });
  }

  private void createFile(final AbstractPath path, final MemoryEntryCreator creator) throws IOException {
    this.checker.check();
    AbstractPath absolutePath = (AbstractPath) path.toAbsolutePath().normalize();
    if (absolutePath.isRoot()) {
      throw new FileSystemException(path.toString(), null, "can not create root");
    }
    final ElementPath elementPath = (ElementPath) absolutePath;
    MemoryDirectory rootDirectory = this.getRootDirectory(elementPath);

    this.withWriteLockOnLastDo(rootDirectory, (AbstractPath) elementPath.getParent(), true, new MemoryDirectoryBlock<Void>() {

      @Override
      public Void value(MemoryDirectory directory) throws IOException {
        String name = MemoryFileSystem.this.storeTransformer.transform(elementPath.getLastNameElement());
        MemoryEntry newEntry = creator.create(name);
        String key = MemoryFileSystem.this.lookUpTransformer.transform(newEntry.getOriginalName());
        directory.addEntry(key, newEntry);
        return null;
      }
    });

  }

  Path toRealPath(AbstractPath abstractPath, LinkOption... options)throws IOException  {
    this.checker.check();
    AbstractPath absolutePath = (AbstractPath) abstractPath.toAbsolutePath().normalize();
    boolean followSymLinks = Options.isFollowSymLinks(options);
    Set<MemorySymbolicLink> encounteredSymlinks;
    if (followSymLinks) {
      // we don't expect to encounter many symlinks so we initialize to a lower than the default value of 16
      // TODO optimized set
      encounteredSymlinks = new HashSet<>(4);
    } else {
      encounteredSymlinks = Collections.emptySet();
    }
    MemoryDirectory root = this.getRootDirectory(absolutePath);
    return this.toRealPath(root, absolutePath, encounteredSymlinks, followSymLinks);
  }


  private Path toRealPath(MemoryDirectory root, AbstractPath path, Set<MemorySymbolicLink> encounteredLinks, boolean followSymLinks) throws IOException {
    if (path.isRoot()) {
      return path.getRoot();
    } else if (path instanceof ElementPath) {
      Path symLinkTarget = null;

      ElementPath elementPath = (ElementPath) path;
      List<String> nameElements = elementPath.getNameElements();
      List<String> realPath = new ArrayList<>(nameElements.size());
      List<AutoRelease> locks = new ArrayList<>(nameElements.size() + 1);
      try {
        locks.add(root.readLock());
        MemoryDirectory parent = root;
        for (int i = 0; i < nameElements.size(); ++i) {
          String fileName = nameElements.get(i);
          String key = this.lookUpTransformer.transform(fileName);
          MemoryEntry current = parent.getEntryOrException(key, path);
          locks.add(current.readLock());
          realPath.add(current.getOriginalName());

          if (followSymLinks && current instanceof MemorySymbolicLink) {
            MemorySymbolicLink link = (MemorySymbolicLink) current;
            if (!encounteredLinks.add(link)) {
              // TODO better error message
              throw new FileSystemLoopException(path.toString());
            }
            symLinkTarget = link.getTarget();
          }

          if (i == nameElements.size() - 1) {
            continue;
          } else if (current instanceof MemoryDirectory) {
            parent = (MemoryDirectory) current;
          } else {
            //TODO construct better error message
            throw new NotDirectoryException(path.toString());
          }

        }
      } finally {
        for (int i = locks.size() - 1; i >= 0; --i) {
          AutoRelease lock = locks.get(i);
          lock.close();
        }
      }
      if (symLinkTarget == null) {
        return AbsolutePath.createAboslute(this, (Root) path.getRoot(), realPath);
      } else {
        return this.toRealPath(root, (AbstractPath) symLinkTarget, encounteredLinks, followSymLinks);
      }

    } else {
      throw new IllegalArgumentException("unknown path type" + path);
    }
  }

  void checkAccess(AbstractPath path, final AccessMode... modes) throws IOException {
    this.checker.check();
    // java.nio.file.spi.FileSystemProvider#checkAccess(Path, AccessMode...)
    // says we should follow symbolic links
    this.accessFileReading(path, true, new MemoryEntryBlock<Void>() {

      @Override
      public Void value(MemoryEntry entry) throws IOException {
        entry.checkAccess(modes);
        return null;
      }
    });
  }

  <A extends BasicFileAttributes> A readAttributes(AbstractPath path, final Class<A> type, LinkOption... options) throws IOException {
    this.checker.check();
    return this.accessFileReading(path, Options.isFollowSymLinks(options), new MemoryEntryBlock<A>() {

      @Override
      public A value(MemoryEntry entry) throws IOException {
        return entry.readAttributes(type);
      }
    });
  }

  <V extends FileAttributeView> V getLazyFileAttributeView(AbstractPath path, final Class<V> type, LinkOption... options) {
    InvocationHandler handler = new LazyFileAttributeView<>(path, type, options);
    Object proxy = Proxy.newProxyInstance(MemoryFileSystem.class.getClassLoader(), new Class<?>[]{type}, handler);
    return type.cast(proxy);
  }

  <V extends FileAttributeView> V getFileAttributeView(AbstractPath path, final Class<V> type, LinkOption... options) throws IOException {
    return this.accessFileReading(path, Options.isFollowSymLinks(options), new MemoryEntryBlock<V>() {

      @Override
      public V value(MemoryEntry entry) throws IOException {
        return entry.getFileAttributeView(type);
      }
    });
  }

  Map<String, Object> readAttributes(AbstractPath path, final String attributes, LinkOption... options) throws IOException {
    this.checker.check();
    return this.accessFileReading(path, Options.isFollowSymLinks(options), new MemoryEntryBlock<Map<String, Object>>() {

      @Override
      public Map<String, Object> value(MemoryEntry entry) throws IOException {
        return AttributeAccessors.readAttributes(entry, attributes);
      }
    });
  }

  void setAttribute(AbstractPath path, final String attribute, final Object value, LinkOption... options) throws IOException {
    this.checker.check();
    this.accessFileWriting(path, Options.isFollowSymLinks(options), new MemoryEntryBlock<Void>() {

      @Override
      public Void value(MemoryEntry entry) throws IOException {
        // TODO write lock?
        AttributeAccessors.setAttribute(entry, attribute, value);
        return null;
      }
    });
  }

  private <R> R accessFileReading(AbstractPath path, boolean followSymLinks, MemoryEntryBlock<? extends R> callback) throws IOException {
    return this.accessFile(path, followSymLinks, LockType.READ, callback);
  }

  private <R> R accessFileWriting(AbstractPath path, boolean followSymLinks, MemoryEntryBlock<? extends R> callback) throws IOException {
    return this.accessFile(path, followSymLinks, LockType.WRITE, callback);
  }

  private <R> R accessFile(AbstractPath path, boolean followSymLinks, LockType lockType, MemoryEntryBlock<? extends R> callback) throws IOException {
    this.checker.check();
    AbstractPath absolutePath = (AbstractPath) path.toAbsolutePath().normalize();
    MemoryDirectory directory = this.getRootDirectory(absolutePath);
    if (lockType == LockType.READ) {
      return this.withReadLockDo(directory, absolutePath, followSymLinks, callback);
    } else {
      MemoryDirectory rootDirectory = this.getRootDirectory(absolutePath);
      if (absolutePath.isRoot()) {
        try (AutoRelease autoRelease = rootDirectory.writeLock()) {
          return callback.value(rootDirectory);
        }
      }
      ElementPath elementPath = (ElementPath) absolutePath;
      Set<MemorySymbolicLink> encounteredSymlinks;
      if (followSymLinks) {
        encounteredSymlinks = new HashSet<>(4);
      } else {
        encounteredSymlinks = Collections.emptySet();
      }
      return this.withLockDo(rootDirectory, elementPath, encounteredSymlinks, followSymLinks, LockType.WRITE, callback);
    }
  }


  private <R> R withWriteLockOnLastDo(MemoryDirectory root, final AbstractPath path,  boolean followSymLinks, final MemoryDirectoryBlock<R> callback) throws IOException {
    Set<MemorySymbolicLink> encounteredSymlinks;
    if (followSymLinks) {
      encounteredSymlinks = new HashSet<>(4);
    } else {
      encounteredSymlinks = Collections.emptySet();
    }
    return this.withLockDo(root, path, encounteredSymlinks, followSymLinks, LockType.WRITE, new MemoryEntryBlock<R>() {

      @Override
      public R value(MemoryEntry entry) throws IOException {
        if (!(entry instanceof MemoryDirectory)) {
          throw new NotDirectoryException(path.toString());
        }
        return callback.value((MemoryDirectory) entry);
      }
    });

  }

  private <R> R withReadLockDo(MemoryDirectory root, AbstractPath path, boolean followSymLinks, MemoryEntryBlock<? extends R> callback) throws IOException {
    Set<MemorySymbolicLink> encounteredSymlinks;
    if (followSymLinks) {
      encounteredSymlinks = new HashSet<>(4);
    } else {
      encounteredSymlinks = Collections.emptySet();
    }
    return this.withLockDo(root, path, encounteredSymlinks, followSymLinks, LockType.READ, callback);
  }


  private <R> R withLockDo(MemoryDirectory root, AbstractPath path, Set<MemorySymbolicLink> encounteredLinks, boolean followSymLinks, LockType lockType, MemoryEntryBlock<? extends R> callback) throws IOException {
    if (path.isRoot()) {
      try (AutoRelease lock = root.readLock()) {
        return callback.value(root);
      }
    } else if (path instanceof ElementPath) {
      R result = null;
      Path symLinkTarget = null;

      ElementPath elementPath = (ElementPath) path;
      List<String> nameElements = elementPath.getNameElements();
      List<AutoRelease> locks = new ArrayList<>(nameElements.size() + 1);
      try {
        locks.add(root.readLock());
        MemoryDirectory parent = root;
        for (int i = 0; i < nameElements.size(); ++i) {
          String fileName = nameElements.get(i);
          String key = this.lookUpTransformer.transform(fileName);
          MemoryEntry current = parent.getEntryOrException(key, path);
          boolean isLast = i == nameElements.size() - 1;
          if (isLast && lockType == LockType.WRITE) {
            locks.add(current.writeLock());
          } else {
            locks.add(current.readLock());
          }

          if (followSymLinks && current instanceof MemorySymbolicLink) {
            MemorySymbolicLink link = (MemorySymbolicLink) current;
            if (!encounteredLinks.add(link)) {
              // TODO better error message
              throw new FileSystemLoopException(path.toString());
            }
            symLinkTarget = link.getTarget();
          }

          if (isLast) {
            result = callback.value(current);
          } else if (current instanceof MemoryDirectory) {
            parent = (MemoryDirectory) current;
          } else {
            //TODO construct better error message
            throw new NotDirectoryException(path.toString());
          }

        }
      } finally {
        for (int i = locks.size() - 1; i >= 0; --i) {
          AutoRelease lock = locks.get(i);
          lock.close();
        }
      }
      if (symLinkTarget == null) {
        return result;
      } else {
        return this.withLockDo(root, (AbstractPath) symLinkTarget, encounteredLinks, followSymLinks, lockType, callback);
      }

    } else {
      throw new IllegalArgumentException("unknown path type" + path);
    }
  }

  private MemoryDirectory getRootDirectory(AbstractPath path) throws IOException {
    Path root = path.getRoot();
    MemoryDirectory directory = this.roots.get(root);
    if (directory == null) {
      throw new NoSuchFileException(path.toString(), null, "the root doesn't exist");
    }
    return directory;
  }

  void copyOrMove(AbstractPath source, AbstractPath target, TwoPathOperation operation, CopyOption... options) throws IOException {
    try (AutoRelease autoRelease = autoRelease(this.pathOrderingLock.writeLock())) {

      EndPointCopyContext sourceContext = this.buildEndpointCopyContext(source);
      EndPointCopyContext targetContext = this.buildEndpointCopyContext(target);

      int order = this.orderPaths(sourceContext, targetContext);
      final CopyContext copyContext = buildCopyContext(sourceContext, targetContext, operation, options, order);

      MemoryDirectory firstRoot = this.getRootDirectory(copyContext.first.parent);
      final MemoryDirectory secondRoot = this.getRootDirectory(copyContext.second.parent);
      this.withWriteLockOnLastDo(firstRoot, copyContext.first.parent, copyContext.firstFollowSymLinks, new MemoryDirectoryBlock<Void>() {

        @Override
        public Void value(final MemoryDirectory firstDirectory) throws IOException {
          MemoryFileSystem.this.withWriteLockOnLastDo(secondRoot, copyContext.second.parent, copyContext.secondFollowSymLinks, new MemoryDirectoryBlock<Void>() {

            @Override
            public Void value(MemoryDirectory secondDirectory) throws IOException {
              handleTwoPathOperation(copyContext, firstDirectory, secondDirectory);
              return null;
            }

          });
          return null;
        }

      });

    }
  }

  static void copyOrMoveBetweenFileSystems(MemoryFileSystem sourceFileSystem, MemoryFileSystem targetFileSystem, AbstractPath source, AbstractPath target, TwoPathOperation operation, CopyOption... options) throws IOException {
    EndPointCopyContext sourceContext = sourceFileSystem.buildEndpointCopyContext(source);
    EndPointCopyContext targetContext = targetFileSystem.buildEndpointCopyContext(target);

    int order = orderFileSystems(sourceContext, targetContext);
    final CopyContext copyContext = buildCopyContext(sourceContext, targetContext, operation, options, order);

    MemoryDirectory firstRoot = sourceFileSystem.getRootDirectory(copyContext.first.parent);
    final MemoryDirectory secondRoot = targetFileSystem.getRootDirectory(copyContext.second.parent);
    copyContext.first.path.getMemoryFileSystem().withWriteLockOnLastDo(firstRoot, copyContext.first.parent, copyContext.firstFollowSymLinks, new MemoryDirectoryBlock<Void>() {

      @Override
      public Void value(final MemoryDirectory firstDirectory) throws IOException {
        copyContext.second.path.getMemoryFileSystem().withWriteLockOnLastDo(secondRoot, copyContext.second.parent, copyContext.secondFollowSymLinks, new MemoryDirectoryBlock<Void>() {

          @Override
          public Void value(MemoryDirectory secondDirectory) throws IOException {
            handleTwoPathOperation(copyContext, firstDirectory, secondDirectory);
            return null;
          }

        });
        return null;
      }

    });
  }

  private int orderPaths(EndPointCopyContext source, EndPointCopyContext target) {
    int parentOrder = source.parent.compareTo(target.parent);
    if (parentOrder != 0) {
      return parentOrder;
    } else {
      return MemoryFileSystem.this.collator.compare(source.elementName, target.elementName);
    }
  }

  private static int orderFileSystems(EndPointCopyContext source, EndPointCopyContext target) {
    MemoryFileSystem sourceFileSystem = source.path.getMemoryFileSystem();
    MemoryFileSystem targetFileSystem = target.path.getMemoryFileSystem();
    String sourceKey = sourceFileSystem.getKey();
    String targetKey = targetFileSystem.getKey();
    int comparison = sourceKey.compareTo(targetKey);
    if (comparison != 0) {
      return comparison;
    } else {
      throw new AssertionError("the two file system keys " + sourceKey + " and " + targetKey + " compare equal.");
    }
  }

  private EndPointCopyContext buildEndpointCopyContext(AbstractPath path) {
    // TODO check for root
    ElementPath absolutePath = (ElementPath) path.toAbsolutePath().normalize();
    AbstractPath parent = (AbstractPath) absolutePath.getParent();
    String elementName = absolutePath.getLastNameElement();
    return new EndPointCopyContext(absolutePath, parent, elementName);
  }

  static final class EndPointCopyContext {

    final AbstractPath path;
    final AbstractPath parent;
    final String elementName;

    EndPointCopyContext(AbstractPath path, AbstractPath parent, String elementName) {
      this.path = path;
      this.parent = parent;
      this.elementName = elementName;
    }

  }

  private static CopyContext buildCopyContext(EndPointCopyContext source, EndPointCopyContext target, TwoPathOperation operation, CopyOption[] options, int order) {
    boolean followSymLinks = Options.isFollowSymLinks(options);
    boolean replaceExisting = Options.isReplaceExisting(options);
    boolean copyAttribues = Options.isCopyAttribues(options);

    EndPointCopyContext first;
    EndPointCopyContext second;
    boolean firstFollowSymLinks;
    boolean secondFollowSymLinks;
    boolean inverted;
    if (order <= 0) {
      first = source;
      second = target;
      firstFollowSymLinks = followSymLinks;
      secondFollowSymLinks = false;
      inverted = false;
    } else {
      first = target;
      second = source;
      firstFollowSymLinks = false;
      secondFollowSymLinks = followSymLinks;
      inverted = true;
    }

    return new CopyContext(operation, source, target, first, second, firstFollowSymLinks, secondFollowSymLinks,
            inverted, replaceExisting, copyAttribues);
  }

  static final class CopyContext {

    final EndPointCopyContext source;
    final EndPointCopyContext target;
    final EndPointCopyContext first;
    final EndPointCopyContext second;
    final boolean firstFollowSymLinks;
    final boolean secondFollowSymLinks;
    private final boolean inverted;
    final boolean replaceExisting;
    final boolean copyAttribues;
    final TwoPathOperation operation;

    CopyContext(TwoPathOperation operation, EndPointCopyContext source, EndPointCopyContext target, EndPointCopyContext first, EndPointCopyContext second,
            boolean firstFollowSymLinks, boolean secondFollowSymLinks, boolean inverted, boolean replaceExisting, boolean copyAttribues) {
      this.operation = operation;
      this.source = source;
      this.target = target;
      this.first = first;
      this.second = second;
      this.firstFollowSymLinks = firstFollowSymLinks;
      this.secondFollowSymLinks = secondFollowSymLinks;
      this.inverted = inverted;
      this.replaceExisting = replaceExisting;
      this.copyAttribues = copyAttribues;
    }

    MemoryDirectory getSourceParent(MemoryDirectory firstDirectory, MemoryDirectory secondDirectory) {
      if (!this.inverted) {
        return firstDirectory;
      } else {
        return secondDirectory;
      }
    }

    MemoryDirectory getTargetParent(MemoryDirectory firstDirectory, MemoryDirectory secondDirectory) {
      if (!this.inverted) {
        return secondDirectory;
      } else {
        return firstDirectory;
      }
    }



  }


  void delete(final AbstractPath abstractPath) throws IOException {
    try (AutoRelease autoRelease = autoRelease(this.pathOrderingLock.readLock())) {
      AbstractPath absolutePath = (AbstractPath) abstractPath.toAbsolutePath().normalize();
      if (absolutePath.isRoot()) {
        throw new FileSystemException(abstractPath.toString(), null, "can not delete root");
      }
      final ElementPath elementPath = (ElementPath) absolutePath;
      MemoryDirectory rootDirectory = this.getRootDirectory(elementPath);

      this.withWriteLockOnLastDo(rootDirectory, (AbstractPath) elementPath.getParent(), true, new MemoryDirectoryBlock<Void>() {

        @Override
        public Void value(MemoryDirectory directory) throws IOException {
          String fileName = elementPath.getLastNameElement();
          String key = MemoryFileSystem.this.lookUpTransformer.transform(fileName);
          MemoryEntry child = directory.getEntryOrException(key, abstractPath);
          try (AutoRelease lock = child.writeLock()) {
            if (child instanceof MemoryDirectory) {
              MemoryDirectory childDirectory = (MemoryDirectory) child;
              childDirectory.checkEmpty(abstractPath);
            }
            if (child instanceof MemoryFile) {
              MemoryFile file = (MemoryFile) child;
              if (file.openCount() > 0) {
                throw new FileSystemException(abstractPath.toString(), null, "file still open");
              }
              file.markForDeletion();
            }
            directory.removeEntry(key);
          }
          return null;
        }
      });
    }
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
    return this.supportedFileAttributeViews;
  }


  @Override
  public AbstractPath getPath(String first, String... more) {
    this.checker.check();
    // TODO check for maximum length
    return this.pathParser.parse(this.rootByKey, first, more);
  }


  @Override
  public PathMatcher getPathMatcher(String syntaxAndPattern) {
    this.checker.check();
    int colonIndex = syntaxAndPattern.indexOf(':');
    if (colonIndex <= 0 || colonIndex == syntaxAndPattern.length() - 1) {
      throw new IllegalArgumentException("syntaxAndPattern must have form \"syntax:pattern\" but was \"" + syntaxAndPattern + "\"");
    }

    String syntax = syntaxAndPattern.substring(0, colonIndex);
    String pattern = syntaxAndPattern.substring(colonIndex + 1);
    if (syntax.equalsIgnoreCase(GlobPathMatcher.name())) {
      Path patternPath = this.getPath(pattern);
      return new GlobPathMatcher(patternPath);
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
    // TODO make configurable
    throw new UnsupportedOperationException();
  }


  FileStore getFileStore() {
    return this.store;
  }

  Collator getCollator() {
    return this.collator;
  }

  boolean isHidden(AbstractPath abstractPath) throws IOException {
    // Posix seems to check only the file name
    // TODO write test
    return this.accessFileReading(abstractPath, false, new MemoryEntryBlock<Boolean>(){

      @Override
      public Boolean value(MemoryEntry entry) throws IOException {
        Set<String> supportedFileAttributeViews = MemoryFileSystem.this.supportedFileAttributeViews();
        if (supportedFileAttributeViews.contains(FileAttributeViews.POSIX)) {
          String originalName = entry.getOriginalName();
          return !originalName.isEmpty() && originalName.charAt(0) == '.';
        } else if (supportedFileAttributeViews.contains(FileAttributeViews.DOS)) {
          return entry.readAttributes(DosFileAttributes.class).isHidden();
        } else {
          return false;
        }
      }

    });
  }

  private MemoryEntry copyEntry(Path absoluteTargetPath, MemoryEntry sourceEntry, String targetElementName) throws IOException {
    if (sourceEntry instanceof MemoryFile) {
      MemoryFile sourceFile = (MemoryFile) sourceEntry;
      try (AutoRelease lock = sourceFile.readLock()) {
        return new MemoryFile(targetElementName, this.newEntryCreationContext(), sourceFile);
      }
    } else if (sourceEntry instanceof MemoryDirectory) {
      MemoryDirectory sourceDirectory = (MemoryDirectory) sourceEntry;
      try (AutoRelease lock = sourceDirectory.readLock()) {
        sourceDirectory.checkEmpty(absoluteTargetPath);
        return new MemoryDirectory(targetElementName, MemoryFileSystem.this.newEntryCreationContext());
      }
    } else if (sourceEntry instanceof MemorySymbolicLink) {
      MemorySymbolicLink sourceLink = (MemorySymbolicLink) sourceEntry;
      try (AutoRelease lock = sourceLink.readLock()) {
        return new MemorySymbolicLink(targetElementName, (AbstractPath) sourceLink.getTarget(), MemoryFileSystem.this.newEntryCreationContext());
      }
    } else {
      throw new AssertionError("unknown entry type:" + sourceEntry);
    }
  }

  public MemorySymbolicLink readSymbolicLink(final AbstractPath path) throws IOException {
    AbstractPath absolutePath = (AbstractPath) path.toAbsolutePath().normalize();
    if (absolutePath.isRoot()) {
      throw new FileSystemException(path.toString(), null, "is not a file");
    }
    final ElementPath elementPath = (ElementPath) absolutePath;
    MemoryDirectory rootDirectory = this.getRootDirectory(absolutePath);
    return this.withWriteLockOnLastDo(rootDirectory, (AbstractPath) absolutePath.getParent(), true, new MemoryDirectoryBlock<MemorySymbolicLink>() {

      @Override
      public MemorySymbolicLink value(MemoryDirectory directory) throws IOException {
        String fileName = elementPath.getLastNameElement();
        String key = MemoryFileSystem.this.lookUpTransformer.transform(fileName);
        MemoryEntry storedEntry = directory.getEntry(key);
        if (storedEntry == null) {
          throw new NoSuchFileException(path.toString(), null, "the file doesn't exist");
        }
        if (!(storedEntry instanceof MemorySymbolicLink)) {
          throw new IOException("file is not a symbolic link");
        }
        return (MemorySymbolicLink) storedEntry;
      }
    });
  }

  static void handleTwoPathOperation(CopyContext copyContext, MemoryDirectory firstDirectory, MemoryDirectory secondDirectory) throws IOException {

    EndPointCopyContext sourceContext = copyContext.source;
    EndPointCopyContext targetContext = copyContext.target;
    MemoryDirectory sourceParent = copyContext.getSourceParent(firstDirectory, secondDirectory);
    MemoryDirectory targetParent = copyContext.getTargetParent(firstDirectory, secondDirectory);

    StringTransformer sourceTransformer = sourceContext.path.getMemoryFileSystem().lookUpTransformer;
    String sourceElementName = sourceTransformer.transform(sourceContext.elementName);
    MemoryEntry sourceEntry = sourceParent.getEntryOrException(sourceElementName, sourceContext.path);

    StringTransformer targetTransformer = targetContext.path.getMemoryFileSystem().lookUpTransformer;
    String targetElementName = targetTransformer.transform(targetContext.elementName);
    MemoryEntry targetEntry = targetParent.getEntry(targetElementName);

    if (sourceEntry == targetEntry) {
      // source and target are the same, do nothing
      // the way I read Files#copy this is the intention of the spec
      return;
    }

    if (targetEntry != null) {
      if (!copyContext.replaceExisting) {
        throw new FileAlreadyExistsException(targetContext.path.toString());
      }

      if (targetEntry instanceof MemoryDirectory) {
        MemoryDirectory targetDirectory = (MemoryDirectory) targetEntry;
        try (AutoRelease lock = targetDirectory.readLock()) {
          targetDirectory.checkEmpty(targetContext.path);
        }
      }
      targetParent.removeEntry(targetElementName);
    }

    if (copyContext.operation.isMove()) {
      sourceParent.removeEntry(sourceElementName);
      targetParent.addEntry(targetElementName, sourceEntry);
    } else {
      MemoryEntry copy = targetContext.path.getMemoryFileSystem().copyEntry(targetContext.path, sourceEntry, targetElementName);
      if (copyContext.copyAttribues) {
        copy.initializeAttributes(sourceEntry);
      }
      targetParent.addEntry(targetElementName, copy);
    }
  }

  @Override
  public String toString() {
    return MemoryFileSystem.class.getSimpleName() + '[' + this.key + ']';
  }

  class LazyFileAttributeView<V extends FileAttributeView> implements InvocationHandler {

    private final AbstractPath path;
    private final LinkOption[] options;
    private final Class<V> type;
    private final AtomicReference<V> attributeView;

    LazyFileAttributeView(AbstractPath path, Class<V> type, LinkOption... options) {
      this.path = path;
      this.options = options;
      this.type = type;
      this.attributeView = new AtomicReference<>();
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      String methodName = method.getName();
      switch (methodName) {
        case "name":
          if (args != null && args.length > 0) {
            throw new AssertionError("#name() not expected to have any arguments");
          }
          return FileAttributeViews.mapAttributeView(this.type);
        case "toString":
          if (args != null && args.length > 0) {
            throw new AssertionError("#toString() not expected to have any arguments");
          }
          return this.type.toString();
        case "equals":
          if (args == null || args.length != 1) {
            throw new AssertionError("#equals() expected to exactly one argument");
          }
          return proxy == args[0];
        case "hashCode":
          if (args != null && args.length > 0) {
            throw new AssertionError("#hashCode() not expected to have any arguments");
          }
          return System.identityHashCode(proxy);
        default:
          try {
            return method.invoke(this.getView(), args);
          } catch (InvocationTargetException e) {
            throw e.getCause();
          }
      }
    }

    private V getView() throws IOException {
      V v = this.attributeView.get();
      if (v != null) {
        return v;
      } else {
        V newValue = MemoryFileSystem.this.getFileAttributeView(this.path, this.type, this.options);
        boolean successful = this.attributeView.compareAndSet(null, newValue);
        if (successful) {
          return newValue;
        } else {
          return this.attributeView.get();
        }
      }
    }

  }


  interface MemoryEntryBlock<R> {

    R value(MemoryEntry entry) throws IOException;

  }

  interface MemoryDirectoryBlock<R> {

    R value(MemoryDirectory entry) throws IOException;

  }

  interface MemoryEntryCreator {

    MemoryEntry create(String name) throws IOException;

  }

  enum LockType {
    READ,
    WRITE;
  }

}
