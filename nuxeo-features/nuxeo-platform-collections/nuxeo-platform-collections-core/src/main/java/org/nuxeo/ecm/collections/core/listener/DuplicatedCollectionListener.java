/*
 * (C) Copyright 2014 Nuxeo SA (http://nuxeo.com/) and contributors.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl-2.1.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * Contributors:
 *     <a href="mailto:grenard@nuxeo.com">Guillaume</a>
 */
package org.nuxeo.ecm.collections.core.listener;

import static org.nuxeo.ecm.core.api.CoreSession.ALLOW_VERSION_WRITE;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.nuxeo.ecm.collections.api.CollectionConstants;
import org.nuxeo.ecm.collections.api.CollectionManager;
import org.nuxeo.ecm.collections.core.adapter.CollectionMember;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentModelList;
import org.nuxeo.ecm.core.api.DocumentRef;
import org.nuxeo.ecm.core.api.event.DocumentEventTypes;
import org.nuxeo.ecm.core.event.Event;
import org.nuxeo.ecm.core.event.EventContext;
import org.nuxeo.ecm.core.event.EventListener;
import org.nuxeo.ecm.core.event.impl.DocumentEventContext;
import org.nuxeo.ecm.core.query.sql.NXQL;
import org.nuxeo.runtime.api.Framework;

/**
 * Event handler to duplicate the collection members of a duplicated collection. The handler is synchronous because it
 * is important to capture the collection member ids of the duplicated collection at the exact moment of duplication. We
 * don't want to duplicate a collection member that was indeed added to the duplicated collection after the duplication.
 * The handler will then launch asynchronous tasks to duplicate the collection members.
 *
 * @since 5.9.3
 */
public class DuplicatedCollectionListener implements EventListener {

    private static final Log log = LogFactory.getLog(DuplicatedCollectionListener.class);

    @Override
    public void handleEvent(Event event) {
        EventContext ctx = event.getContext();
        if (!(ctx instanceof DocumentEventContext)) {
            return;
        }

        final String eventId = event.getName();

        final DocumentEventContext docCxt = (DocumentEventContext) event.getContext();

        DocumentModel doc = null;
        if (eventId.equals(DocumentEventTypes.DOCUMENT_CREATED_BY_COPY)) {
            doc = docCxt.getSourceDocument();
        } else if (eventId.equals(DocumentEventTypes.DOCUMENT_CHECKEDIN)) {
            DocumentRef checkedInVersionRef = (DocumentRef) ctx.getProperties().get("checkedInVersionRef");
            doc = ctx.getCoreSession().getDocument(checkedInVersionRef);
            if (!doc.isVersion()) {
                return;
            }
        } else {
            return;
        }

        final CollectionManager collectionManager = Framework.getLocalService(CollectionManager.class);

        if (collectionManager.isCollection(doc)) {

            if (eventId.equals(DocumentEventTypes.DOCUMENT_CREATED_BY_COPY)) {
                log.trace(String.format("Collection %s copied", doc.getId()));
            } else if (eventId.equals(DocumentEventTypes.DOCUMENT_CHECKEDIN)) {
                log.trace(String.format("Collection %s checked in", doc.getId()));
            }

            collectionManager.processCopiedCollection(doc);

        }

        if (collectionManager.isCollected(doc)) {
            processCopiedMember(doc, ctx.getCoreSession());
        }

        if (doc.isFolder()) {
            // We just copied a folder, maybe among the descendants there are collections that have been copied too
            // proceed to the deep collection copy
            int offset = 0;
            DocumentModelList deepCopiedCollections;
            CoreSession session = ctx.getCoreSession();
            do {
                deepCopiedCollections = session.query(
                        "SELECT * FROM Document WHERE ecm:mixinType = 'Collection' AND ecm:path STARTSWITH "
                                + NXQL.escapeString(doc.getPathAsString()) + " ORDER BY ecm:uuid",
                        null, CollectionAsynchrnonousQuery.MAX_RESULT, offset, false);
                offset += deepCopiedCollections.size();
                for (DocumentModel deepCopiedCollection : deepCopiedCollections) {
                    collectionManager.processCopiedCollection(deepCopiedCollection);
                }
            } while (deepCopiedCollections.size() >= CollectionAsynchrnonousQuery.MAX_RESULT);

            // Maybe among the descendants there are collection members that have been copied too
            // Let's make sure they don't belong to their original document's collections
            offset = 0;
            DocumentModelList deepCopiedMembers;
            do {
                // CollectionMember is a dynamically added facet. Using it in where clause does not scale.
                // Better check existence of collectionMember:collectionIds property to detect copied members
                deepCopiedMembers = session.query(
                        "SELECT * FROM Document WHERE " + CollectionConstants.DOCUMENT_COLLECTION_IDS_PROPERTY_NAME
                                + "/* IS NOT NULL AND ecm:path STARTSWITH " + NXQL.escapeString(doc.getPathAsString())
                                + " ORDER BY ecm:uuid",
                        null, CollectionAsynchrnonousQuery.MAX_RESULT, offset, false);
                offset += deepCopiedMembers.size();
                for (DocumentModel deepCopiedMember : deepCopiedMembers) {
                    processCopiedMember(deepCopiedMember, session);
                }
            } while (deepCopiedMembers.size() >= CollectionAsynchrnonousQuery.MAX_RESULT);
        }
    }

    /**
     * @since 8.4
     */
    private void processCopiedMember(DocumentModel doc, CoreSession session) {
        if (!Framework.getLocalService(CollectionManager.class).isCollected(doc)) {
            // should never happen but we may have dirty members which have no longer the CollectionMember facet but
            // sill collectionMember:collectionIds valued
            doc.setPropertyValue(CollectionConstants.DOCUMENT_COLLECTION_IDS_PROPERTY_NAME, null);
        } else {
            doc.getAdapter(CollectionMember.class).setCollectionIds(null);
        }
        if (doc.isVersion()) {
            doc.putContextData(ALLOW_VERSION_WRITE, Boolean.TRUE);
        }
        doc = session.saveDocument(doc);
        doc.removeFacet(CollectionConstants.COLLECTABLE_FACET);
        doc = session.saveDocument(doc);
    }

}