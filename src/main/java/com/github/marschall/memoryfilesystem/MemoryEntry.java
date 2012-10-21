package com.github.marschall.memoryfilesystem;

import static com.github.marschall.memoryfilesystem.AutoReleaseLock.autoRelease;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.AccessMode;
import java.nio.file.FileSystemException;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.FileOwnerAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserDefinedFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

abstract class MemoryEntry {

  private final String originalName;

  // protected by read and write locks
  private FileTime lastModifiedTime;
  private FileTime lastAccessTime;
  private FileTime creationTime;

  private final ReadWriteLock lock;

  private Map <String, BasicFileAttributeView> additionalAttributes;

  MemoryEntry(String originalName) {
    this.originalName = originalName;
    this.lock = new ReentrantReadWriteLock();
    FileTime now = this.getNow();
    this.lastAccessTime = now;
    this.lastModifiedTime = now;
    this.creationTime = now;
  }


  String getOriginalName() {
    return this.originalName;
  }

  private FileTime getNow() {
    long now = System.currentTimeMillis();
    return FileTime.fromMillis(now);
  }


  AutoRelease readLock() {
    return autoRelease(this.lock.readLock());
  }

  AutoRelease writeLock() {
    return autoRelease(this.lock.writeLock());
  }

  FileTime lastModifiedTime() {
    try (AutoRelease lock = this.readLock()) {
      return this.lastModifiedTime;
    }
  }

  FileTime lastAccessTime() {
    try (AutoRelease lock = this.readLock()) {
      return this.lastAccessTime;
    }
  }

  FileTime creationTime() {
    try (AutoRelease lock = this.readLock()) {
      return this.creationTime;
    }
  }

  void checkAccess(AccessMode... modes) {
    try (AutoRelease lock = this.readLock()) {
      AccessMode unsupported = this.getUnsupported(modes);
      if (unsupported != null) {
        throw new UnsupportedOperationException("access mode " + unsupported + " is not supported");
      }
      // TODO implement
      // throw new AccessDeniedException
    }
  }

  private AccessMode getUnsupported(AccessMode... modes) {
    for (AccessMode mode : modes) {
      if (!(mode == AccessMode.READ || mode == AccessMode.WRITE || mode == AccessMode.EXECUTE)) {
        return mode;
      }
    }
    return null;
  }

  void modified() {
    // No write lock because this was to be folded in an operation with a write lock
    FileTime now = this.getNow();
    this.lastAccessTime = now;
    this.lastModifiedTime = now;
  }

  void accessed() {
    // No write lock because this was to be folded in an operation with a write lock
    this.lastAccessTime = this.getNow();
  }

  void setTimes(FileTime lastModifiedTime, FileTime lastAccessTime, FileTime createTime) {
    if (lastModifiedTime == null) {
      throw new IllegalArgumentException("lastModifiedTime must not be null");
    }
    if (lastAccessTime == null) {
      throw new IllegalArgumentException("lastAccessTime must not be null");
    }
    if (createTime == null) {
      throw new IllegalArgumentException("createTime must not be null");
    }
    try (AutoRelease lock = this.writeLock()) {
      this.checkAccess(AccessMode.WRITE);
      this.lastModifiedTime = lastModifiedTime;
      this.lastAccessTime = lastAccessTime;
      this.creationTime = createTime;
    }
  }


  <A extends BasicFileAttributes> A readAttributes(Class<A> type) {
    if (type == BasicFileAttributes.class) {
      this.accessed();
      return (A) this.getBasicFileAttributes();
    } else {
      throw new UnsupportedOperationException("file attribute view" + type + " not supported");
    }
  }

  abstract BasicFileAttributeView getBasicFileAttributeView();
  abstract BasicFileAttributes getBasicFileAttributes();

  abstract class MemoryEntryFileAttributesView implements BasicFileAttributeView {

    @Override
    public String name() {
      return FileAttributeViews.BASIC;
    }

    @Override
    public void setTimes(FileTime lastModifiedTime, FileTime lastAccessTime, FileTime createTime) {
      MemoryEntry.this.setTimes(lastModifiedTime, lastAccessTime, createTime);
    }

  }

  abstract class MemoryEntryFileAttributes implements BasicFileAttributes {

    @Override
    public FileTime lastModifiedTime() {
      return MemoryEntry.this.lastModifiedTime();
    }

    @Override
    public FileTime lastAccessTime() {
      return MemoryEntry.this.lastAccessTime();
    }

    @Override
    public FileTime creationTime() {
      return MemoryEntry.this.creationTime();
    }

  }

  abstract class DelegatingFileAttributes implements BasicFileAttributeView, BasicFileAttributes {


    @Override
    public void setTimes(FileTime lastModifiedTime, FileTime lastAccessTime, FileTime createTime) throws IOException {
      MemoryEntry.this.getBasicFileAttributeView().setTimes(lastModifiedTime, lastAccessTime, createTime);
    }

    @Override
    public FileTime lastModifiedTime() {
      return MemoryEntry.this.getBasicFileAttributes().lastModifiedTime();
    }

    @Override
    public FileTime lastAccessTime() {
      return MemoryEntry.this.getBasicFileAttributes().lastAccessTime();
    }

    @Override
    public FileTime creationTime() {
      return MemoryEntry.this.getBasicFileAttributes().creationTime();
    }

    @Override
    public boolean isRegularFile() {
      return MemoryEntry.this.getBasicFileAttributes().isRegularFile();
    }

    @Override
    public boolean isDirectory() {
      return MemoryEntry.this.getBasicFileAttributes().isDirectory();
    }

    @Override
    public boolean isSymbolicLink() {
      return MemoryEntry.this.getBasicFileAttributes().isSymbolicLink();
    }

    @Override
    public boolean isOther() {
      return MemoryEntry.this.getBasicFileAttributes().isOther();
    }

    @Override
    public long size() {
      return MemoryEntry.this.getBasicFileAttributes().size();
    }

    @Override
    public Object fileKey() {
      return MemoryEntry.this.getBasicFileAttributes().fileKey();
    }

  }

  class MemoryDosFileAttributeView extends DelegatingFileAttributes implements DosFileAttributeView, DosFileAttributes {

    private boolean readOnly;
    private boolean hidden;
    private boolean system;
    private boolean archive;

    @Override
    public String name() {
      return "dos";
    }

    @Override
    public DosFileAttributes readAttributes() throws IOException {
      return this;
    }

    @Override
    public void setReadOnly(boolean value) throws IOException {
      try (AutoRelease lock = MemoryEntry.this.writeLock()) {
        MemoryEntry.this.checkAccess(AccessMode.WRITE);
        this.readOnly = value;
      }
    }

    @Override
    public void setHidden(boolean value) throws IOException {
      try (AutoRelease lock = MemoryEntry.this.writeLock()) {
        MemoryEntry.this.checkAccess(AccessMode.WRITE);
        this.hidden = value;
      }

    }

    @Override
    public void setSystem(boolean value) throws IOException {
      try (AutoRelease lock = MemoryEntry.this.writeLock()) {
        MemoryEntry.this.checkAccess(AccessMode.WRITE);
        this.system = value;
      }

    }

    @Override
    public void setArchive(boolean value) throws IOException {
      try (AutoRelease lock = MemoryEntry.this.writeLock()) {
        MemoryEntry.this.checkAccess(AccessMode.WRITE);
        this.archive = value;
      }

    }

    @Override
    public boolean isHidden() {
      try (AutoRelease lock = MemoryEntry.this.readLock()) {
        MemoryEntry.this.checkAccess(AccessMode.WRITE);
        return this.hidden;
      }
    }

    @Override
    public boolean isArchive() {
      try (AutoRelease lock = MemoryEntry.this.readLock()) {
        MemoryEntry.this.checkAccess(AccessMode.WRITE);
        return this.archive;
      }
    }

    @Override
    public boolean isSystem() {
      try (AutoRelease lock = MemoryEntry.this.readLock()) {
        MemoryEntry.this.checkAccess(AccessMode.WRITE);
        return this.system;
      }
    }


    @Override
    public boolean isReadOnly() {
      try (AutoRelease lock = MemoryEntry.this.readLock()) {
        MemoryEntry.this.checkAccess(AccessMode.WRITE);
        return this.readOnly;
      }
    }

  }

  abstract class MemoryFileOwnerAttributeView extends DelegatingFileAttributes implements FileOwnerAttributeView {

    private UserPrincipal owner;

    @Override
    public UserPrincipal getOwner() {
      try (AutoRelease lock = MemoryEntry.this.readLock()) {
        return this.owner;
      }
    }

    @Override
    public void setOwner(UserPrincipal owner) throws IOException {
      // TODO check same file system
      if (owner == null) {
        throw new IllegalArgumentException("owner must not be null");
      }
      try (AutoRelease lock = MemoryEntry.this.writeLock()) {
        MemoryEntry.this.checkAccess(AccessMode.WRITE);
        this.owner = owner;
      }
    }

  }

  class MemoryPosixFileAttributeView extends MemoryFileOwnerAttributeView implements PosixFileAttributeView, PosixFileAttributes {

    private GroupPrincipal group;
    private Set<PosixFilePermission> perms;

    @Override
    public String name() {
      return FileAttributeViews.POSIX;
    }

    @Override
    public GroupPrincipal group() {
      try (AutoRelease lock = MemoryEntry.this.readLock()) {
        return this.group;
      }
    }

    @Override
    public UserPrincipal owner() {
      return this.getOwner();
    }

    @Override
    public void setGroup(GroupPrincipal group) throws IOException {
      // TODO check same file system
      if (group == null) {
        throw new IllegalArgumentException("group must not be null");
      }
      try (AutoRelease lock = MemoryEntry.this.writeLock()) {
        MemoryEntry.this.checkAccess(AccessMode.WRITE);
        this.group = group;
      }
    }

    @Override
    public PosixFileAttributes readAttributes() throws IOException {
      return this;
    }

    @Override
    public Set<PosixFilePermission> permissions() {
      try (AutoRelease lock = MemoryEntry.this.readLock()) {
        return this.perms;
      }
    }

    @Override
    public void setPermissions(Set<PosixFilePermission> perms) throws IOException {
      if (perms == null) {
        throw new IllegalArgumentException("permissions must not be null");
      }
      try (AutoRelease lock = MemoryEntry.this.writeLock()) {
        MemoryEntry.this.checkAccess(AccessMode.WRITE);
        this.perms = perms;
      }
    }

  }

  class MemoryUserDefinedFileAttributeView extends DelegatingFileAttributes implements UserDefinedFileAttributeView {

    // can potentially be null
    // try to delay instantiating as long as possible to keep per file object overhead minimal
    // protected by lock of memory entry
    private Map<String, byte[]> values;

    @Override
    public BasicFileAttributes readAttributes() throws IOException {
      throw new UnsupportedOperationException("readAttributes");
    }

    private Map<String, byte[]> getValues() {
      if (this.values == null) {
        this.values = new HashMap<>(7);
      }
      return this.values;
    }

    @Override
    public String name() {
      return "user";
    }

    @Override
    public List<String> list() throws IOException {
      try (AutoRelease lock = MemoryEntry.this.readLock()) {
        if (this.values == null) {
          return Collections.emptyList();
        } else {
          Set<String> keys = this.getValues().keySet();
          return new ArrayList<String>(keys);
        }
      }
    }

    @Override
    public int size(String name) throws IOException {
      try (AutoRelease lock = MemoryEntry.this.readLock()) {
        byte[] value = this.getValue(name);
        return value.length;
      }
    }

    private byte[] getValue(String name) throws FileSystemException {
      if (name == null) {
        throw new NullPointerException("name is null");
      }
      if (this.values == null) {
        throw new FileSystemException(null, null, "attribute " + name + " not present");
      }
      byte[] value = this.values.get(name);
      if (value == null) {
        throw new FileSystemException(null, null, "attribute " + name + " not present");
      }
      return value;
    }

    @Override
    public int read(String name, ByteBuffer dst) throws IOException {
      try (AutoRelease lock = MemoryEntry.this.readLock()) {
        byte[] value = this.getValue(name);
        int remaining = dst.remaining();
        int required = value.length;
        if (remaining < required) {
          throw new FileSystemException(null, null, required + " bytes in buffer required but only " + remaining + " available");
        }
        int startPosition = dst.position();
        dst.put(value);
        int endPosition = dst.position();
        // TODO check if successful?
        return endPosition - startPosition;
      }
    }

    @Override
    public int write(String name, ByteBuffer src) throws IOException {
      try (AutoRelease lock = MemoryEntry.this.writeLock()) {
        if (name == null) {
          throw new NullPointerException("name is null");
        }
        if (src == null) {
          throw new NullPointerException("buffer is null");
        }
        int remaining = src.remaining();
        byte[] dst = new byte[remaining];
        int startPosition = src.position();
        src.get(dst);
        int endPosition = src.position();
        this.getValues().put(name, dst);
        // TODO check if successful?
        return endPosition - startPosition;
      }
    }

    @Override
    public void delete(String name) throws IOException {
      try (AutoRelease lock = MemoryEntry.this.writeLock()) {
        if (this.values != null) {
          if (name == null) {
            throw new NullPointerException("name is null");
          }
          this.values.remove(name);
        }
      }

    }


  }

}
