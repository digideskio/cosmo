/*
 * Copyright 2007 Open Source Applications Foundation
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
package org.unitedinternet.cosmo.dav.impl;

import org.unitedinternet.cosmo.dav.DavCollection;

/**
 * An interface for DAV collection resources that are backed by content
 * items.
 */
public interface DavItemCollection extends DavItemResource, DavCollection {

    /**
     * @return true if this resource represents a calendar
     * collection.
     */
    boolean isCalendarCollection();

    /**
     * @return true if this resource represents a home collection.
     */
    boolean isHomeCollection();

    /**
     * @return true if this collection does not participate in free-busy
     * query rollups.
     */
    boolean isExcludedFromFreeBusyRollups();
}
