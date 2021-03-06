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
package org.unitedinternet.cosmo.dav.provider;

import java.io.IOException;
import java.net.URISyntaxException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.jackrabbit.webdav.MultiStatus;
import org.apache.jackrabbit.webdav.MultiStatusResponse;
import org.apache.jackrabbit.webdav.property.DavPropertySet;
import org.unitedinternet.cosmo.dav.CosmoDavException;
import org.unitedinternet.cosmo.dav.DavCollection;
import org.unitedinternet.cosmo.dav.DavRequest;
import org.unitedinternet.cosmo.dav.DavResourceFactory;
import org.unitedinternet.cosmo.dav.DavResponse;
import org.unitedinternet.cosmo.dav.ExistsException;
import org.unitedinternet.cosmo.dav.WebDavResource;
import org.unitedinternet.cosmo.dav.caldav.InvalidCalendarLocationException;
import org.unitedinternet.cosmo.dav.caldav.MissingParentException;
import org.unitedinternet.cosmo.dav.impl.DavCalendarCollection;
import org.unitedinternet.cosmo.dav.impl.DavItemCollection;
import org.unitedinternet.cosmo.model.BaseEventStamp;
import org.unitedinternet.cosmo.model.CollectionItem;
import org.unitedinternet.cosmo.model.EntityFactory;
import org.unitedinternet.cosmo.model.Item;
import org.unitedinternet.cosmo.model.NoteItem;
import org.unitedinternet.cosmo.model.Stamp;
import org.unitedinternet.cosmo.model.Ticket;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.ComponentList;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.component.VEvent;

/**
 * <p>
 * An implementation of <code>DavProvider</code> that implements access to <code>DavCalendarCollection</code> resources.
 * </p>
 *
 * @see DavProvider
 * @see DavCalendarCollection
 */
public class CalendarCollectionProvider extends CollectionProvider {
    private static final Log LOG = LogFactory.getLog(CalendarCollectionProvider.class);

    public CalendarCollectionProvider(DavResourceFactory resourceFactory, EntityFactory entityFactory) {
        super(resourceFactory, entityFactory);
    }

    // DavProvider methods
    @Override
    public void mkcalendar(DavRequest request, DavResponse response, DavCollection collection)
            throws CosmoDavException, IOException {
        if (collection.exists()) {
            throw new ExistsException();
        }

        DavItemCollection parent = (DavItemCollection) collection.getParent();
        if (!parent.exists()) {
            throw new MissingParentException("One or more intermediate collections must be created");
        }
        if (parent.isCalendarCollection()) {
            throw new InvalidCalendarLocationException(
                    "A calendar collection may not be created within a calendar collection");
        }
        // XXX DAV:needs-privilege DAV:bind on parent collection

        if (LOG.isDebugEnabled()) {
            LOG.debug("MKCALENDAR at " + collection.getResourcePath());
        }
        DavPropertySet properties = request.getMkCalendarSetProperties();
        MultiStatusResponse msr = collection.getParent().addCollection(collection, properties);

        if (properties.isEmpty() || !hasNonOK(msr)) {
            response.setStatus(201);
            response.setHeader("Cache-control", "no-cache");
            response.setHeader("Pragma", "no-cache");
            return;
        }

        MultiStatus ms = new MultiStatus();
        ms.addResponse(msr);
        response.sendMultiStatus(ms);

    }

    @Override
    protected void spool(DavRequest request, DavResponse response, WebDavResource resource, boolean withEntity)
            throws CosmoDavException, IOException {
        Enumeration<String> acceptHeaders = request.getHeaders("Accept");

        if (acceptHeaders != null) {
            while (acceptHeaders.hasMoreElements()) {
                String headerValue = acceptHeaders.nextElement();
                if ("text/calendar".equalsIgnoreCase(headerValue)) {
                    writeContentOnResponse(response, resource);
                    return;
                }
            }
        }
        super.spool(request, response, resource, withEntity);
    }

    private void writeContentOnResponse(DavResponse response, WebDavResource resource) throws IOException {
        // strip the content if there's a ticket with free-busy access
        if (!(resource instanceof DavCalendarCollection)) {
            throw new IllegalStateException("Incompatible resource type for this provider");
        }
        DavCalendarCollection davCollection = DavCalendarCollection.class.cast(resource);
        CollectionItem collectionItem = (CollectionItem) davCollection.getItem();

        Calendar result = getCalendarFromCollection(collectionItem);

        Ticket contextTicket = getSecurityContext().getTicket();
        Set<Ticket> collectionTickets = collectionItem.getTickets();
        if (contextTicket != null && collectionTickets != null) {
            if (collectionTickets.contains(contextTicket) && contextTicket.isFreeBusy()) {
                result = getFreeBusyCalendar(result);
            }
        }
        
        response.setContentType("text/calendar");
        response.getWriter().write(result.toString());
        response.flushBuffer();
    }

    /**
     * @param result
     * @return
     */
    @SuppressWarnings("unchecked")
    private Calendar getFreeBusyCalendar(Calendar result) {

        try {
            //make a copy of the original calendar
            Calendar freeBusyCal = new Calendar(result);
            ArrayList<Component> events = freeBusyCal.getComponents(Component.VEVENT);
            for (Component event : events) {
                event = getFreeBusyEvent((VEvent) event);
            }
            return freeBusyCal;
        } catch (ParseException | IOException | URISyntaxException e) {
            throw new RuntimeException(e);
        }

    }

    @SuppressWarnings("unchecked")
    private VEvent getFreeBusyEvent(VEvent event) {

        try {
            VEvent freeBusyEvent = event;
            // remove alarms
            freeBusyEvent.getAlarms().clear();

            ArrayList<Property> properties = freeBusyEvent.getProperties();
            for (Property property : properties) {
                hideSensitiveData((Property) property);
            }
            return freeBusyEvent;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * @param property
     * @throws ParseException
     * @throws URISyntaxException
     * @throws IOException
     */
    private void hideSensitiveData(Property property) {

        try {
            switch (property.getName()) {
            case Property.SUMMARY:
                property.setValue("Busy");
                break;
            case Property.DESCRIPTION:
                property.setValue("Busy");
                break;
            case Property.LOCATION:
                property.setValue("Busy");
                break;
            case Property.ATTENDEE:
                property.setValue("");
                break;
            case Property.ORGANIZER:
                property.setValue("");
                break;
            default:
                break;
            }
        } catch (IOException | URISyntaxException | ParseException e) {
            throw new RuntimeException(e);
        }

    }

    /**
     * @param collectionItem
     * @return
     */
    private Calendar getCalendarFromCollection(CollectionItem collectionItem) {

        Calendar result = new Calendar();

        for (Item item : collectionItem.getChildren()) {
            if (!NoteItem.class.isInstance(item)) {
                continue;
            }
            for (Stamp s : item.getStamps()) {
                if (BaseEventStamp.class.isInstance(s)) {
                    BaseEventStamp baseEventStamp = BaseEventStamp.class.cast(s);
                    result.getComponents().add(baseEventStamp.getEvent());
                }
            }
        }
        return result;
    }
}