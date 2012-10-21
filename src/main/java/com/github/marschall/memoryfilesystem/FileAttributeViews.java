package com.github.marschall.memoryfilesystem;

import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.UserDefinedFileAttributeView;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;


final class FileAttributeViews {

  FileAttributeViews() {
    throw new AssertionError("not instantiable");
  }

  static final String POSIX = "posix";
  static final String DOS = "dos";
  static final String BASIC = "basic";
  static final String ACL = "acl";
  static final String USER = "user";
  static final String OWNER = "owner";

  private static final Set<Class<? extends FileAttributeView>> SUPPORTED_VIEWS;
  private static final Set<String> SUPPORTED_VIEW_NAMES;

  private static final Map<String, Class<? extends FileAttributeView>> NAME_TO_CLASS_MAP;
  private static final Map<Class<? extends FileAttributeView>, String> CLASS_TO_NAME_MAP;

  static {
    SUPPORTED_VIEWS = new HashSet<Class<? extends FileAttributeView>>(3);
    SUPPORTED_VIEWS.add(DosFileAttributeView.class);
    SUPPORTED_VIEWS.add(PosixFileAttributeView.class);
    SUPPORTED_VIEWS.add(UserDefinedFileAttributeView.class);

    SUPPORTED_VIEW_NAMES = new HashSet<>(3);
    SUPPORTED_VIEW_NAMES.add(POSIX);
    SUPPORTED_VIEW_NAMES.add(DOS);
    SUPPORTED_VIEW_NAMES.add(USER);

    CLASS_TO_NAME_MAP = new HashMap<>(3);
    CLASS_TO_NAME_MAP.put(PosixFileAttributeView.class, POSIX);
    CLASS_TO_NAME_MAP.put(DosFileAttributeView.class, DOS);
    CLASS_TO_NAME_MAP.put(UserDefinedFileAttributeView.class, USER);
    NAME_TO_CLASS_MAP = new HashMap<>(3);
    NAME_TO_CLASS_MAP.put(POSIX, PosixFileAttributeView.class);
    NAME_TO_CLASS_MAP.put(DOS, DosFileAttributeView.class);
    NAME_TO_CLASS_MAP.put(USER, UserDefinedFileAttributeView.class);
  }

  static boolean isSupported(Class<? extends FileAttributeView> clazz) {
    return SUPPORTED_VIEWS.contains(clazz);
  }

  static boolean isSupported(String name) {
    return SUPPORTED_VIEW_NAMES.contains(name);
  }

  static String map(Class<? extends FileAttributeView> clazz) {
    return CLASS_TO_NAME_MAP.get(clazz);
  }

}
