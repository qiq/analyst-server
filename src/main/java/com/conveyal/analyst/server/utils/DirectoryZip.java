package com.conveyal.analyst.server.utils;

import java.io.*;
import java.net.URI;
import java.util.Deque;
import java.util.LinkedList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class DirectoryZip {

	
	public static void zip(File directory, OutputStream out) throws IOException {
	    URI base = directory.toURI();
	    Deque<File> queue = new LinkedList<File>();
	    queue.push(directory);
	    Closeable res = out;
	    try {
	      ZipOutputStream zout = new ZipOutputStream(out);
	      res = zout;
	      while (!queue.isEmpty()) {
	        directory = queue.pop();
	        for (File kid : directory.listFiles()) {
	          String name = base.relativize(kid.toURI()).getPath();
	          if (kid.isDirectory()) {
	            queue.push(kid);
	            name = name.endsWith("/") ? name : name + "/";
	            zout.putNextEntry(new ZipEntry(name));
	          } else {
	            zout.putNextEntry(new ZipEntry(name));
	            copy(kid, zout);
	            zout.closeEntry();
	          }
	        }
	      }
	    } finally {
	      res.close();
	    }
	  }
	
	
	 private static void copy(InputStream in, OutputStream out) throws IOException {
	    byte[] buffer = new byte[1024];
	    while (true) {
	      int readCount = in.read(buffer);
	      if (readCount < 0) {
	        break;
	      }
	      out.write(buffer, 0, readCount);
	    }
	  }
	
	 private static void copy(File file, OutputStream out) throws IOException {
	    InputStream in = new FileInputStream(file);
	    try {
	      copy(in, out);
	    } finally {
	      in.close();
	    }
	  }

	  private static void copy(InputStream in, File file) throws IOException {
	    OutputStream out = new FileOutputStream(file);
	    try {
	      copy(in, out);
	    } finally {
	      out.close();
	    }
	  }
	
}
