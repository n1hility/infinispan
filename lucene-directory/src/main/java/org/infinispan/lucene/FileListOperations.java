/*
 * JBoss, Home of Professional Open Source
 * Copyright 2010 Red Hat Inc. and/or its affiliates and other
 * contributors as indicated by the @author tags. All rights reserved.
 * See the copyright.txt in the distribution for a full listing of
 * individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.infinispan.lucene;

import java.util.Set;

import org.infinispan.AdvancedCache;
import org.infinispan.context.Flag;
import org.infinispan.util.concurrent.ConcurrentHashSet;

/**
 * Collects operations on the existing fileList, stored as a Set<String> having key
 * of type FileListCacheKey(indexName).
 * 
 * @author Sanne Grinovero
 * @since 4.1
 */
@SuppressWarnings("unchecked")
class FileListOperations {
   
   private final FileListCacheKey fileListCacheKey;
   private final AdvancedCache cache;
   private final String indexName;

   FileListOperations(AdvancedCache cache, String indexName){
      this.cache = cache;
      this.indexName = indexName;
      this.fileListCacheKey = new FileListCacheKey(indexName);
   }
   
   /**
    * @return the current list of files being part of the index 
    */
   Set<String> getFileList() {
      Set<String> fileList = (Set<String>) cache.withFlags(Flag.SKIP_LOCKING).get(fileListCacheKey);
      if (fileList == null) {
         fileList = new ConcurrentHashSet<String>();
         Set<String> prev = (Set<String>) cache.putIfAbsent(fileListCacheKey, fileList);
         return prev == null ? fileList : prev;
      }
      else {
         return fileList;
      }
   }

   /**
    * Deleted a file from the list of files actively part of the index
    * @param fileName
    */
   void deleteFileName(String fileName) {
      Set<String> fileList = getFileList();
      boolean done = fileList.remove(fileName);
      if (done) {
         cache.withFlags(Flag.SKIP_REMOTE_LOOKUP, Flag.SKIP_LOCKING, Flag.SKIP_CACHE_LOAD).put(fileListCacheKey, fileList);
      }
   }
   
   /**
    * Adds a new fileName in the list of files making up this index
    * @param fileName
    */
   void addFileName(String fileName) {
      Set<String> fileList = getFileList();
      boolean done = fileList.add(fileName);
      if (done) {
         cache.withFlags(Flag.SKIP_REMOTE_LOOKUP, Flag.SKIP_CACHE_LOAD, Flag.SKIP_LOCKING).put(fileListCacheKey, fileList);
      }
   }
   
   /**
    * @param fileName
    * @return the FileMetadata associated with the fileName, or null if the file wasn't found.
    */
   FileMetadata getFileMetadata(String fileName) {
      FileCacheKey key = new FileCacheKey(indexName, fileName);
      FileMetadata metadata = (FileMetadata) cache.withFlags(Flag.SKIP_LOCKING).get(key);
      return metadata;
   }

   /**
    * Optimized implementation to perform both a remove and an add 
    * @param toRemove
    * @param toAdd
    */
   void removeAndAdd(String toRemove, String toAdd) {
      Set<String> fileList = getFileList();
      boolean doneAdd = fileList.add(toAdd);
      boolean doneRemove = fileList.remove(toRemove);
      if (doneAdd || doneRemove) {
         cache.withFlags(Flag.SKIP_REMOTE_LOOKUP, Flag.SKIP_CACHE_LOAD, Flag.SKIP_LOCKING).put(fileListCacheKey, fileList);
      }
   }

}
