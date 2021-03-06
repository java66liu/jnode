/*
 * $Id$
 *
 * Copyright (C) 2003-2015 JNode.org
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation; either version 2.1 of the License, or
 * (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful, but 
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public 
 * License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this library; If not, write to the Free Software Foundation, Inc., 
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */
 
package org.jnode.fs.ntfs.security;

import org.jnode.fs.ntfs.NTFSStructure;

/**
 * An access control entry as stored inside a {@link AccessControlList}.
 *
 * @author Luke Quinane
 */
public class AccessControlEntry extends NTFSStructure {

    /**
     * Creates a new ACE from the given ACL data.
     *
     * @param parent the parent ACL.
     * @param offset the offset to this ACE entry.
     */
    public AccessControlEntry(AccessControlList parent, int offset) {
        super(parent, offset);
    }

    /**
     * Gets the type for this entry.
     *
     * @return the type.
     */
    public int getType() {
        return getInt8(0);
    }

    /**
     * Gets the flags for this entry.
     *
     * @return the flags.
     */
    public int getFlags() {
        return getInt8(1);
    }

    /**
     * Gets the size of this entry.
     *
     * @return the size.
     */
    public int getSize() {
        return getInt16(2);
    }

    /**
     * Gets the access mask.
     *
     * @return the access mask.
     */
    public int getAccessMask() {
        return getInt32(4);
    }

    /**
     * Gets the SID associated with this entry.
     *
     * @return the SID.
     */
    public SecurityIdentifier getSid() {
        return SecurityUtils.readSid(this, 8);
    }

    /**
     * ACE types.
     */
    public static class Type {
        public static final int ALLOW = 0x00;
        public static final int DENY = 0x01;
        public static final int AUDIT = 0x02;
    }

    /**
     * ACE flags.
     */
    public static class Flags {
        public static final int OBJECT_INHERITS = 0x01;
        public static final int CONTAINER_INHERITS = 0x02;
        public static final int DONT_PROPAGATE_INHERIT_ACE = 0x04;
        public static final int INHERIT_ONLY_ACE = 0x08;

        public static final int AUDIT_SUCCESS = 0x40;
        public static final int AUDIT_FAILURE = 0x80;
    }
}
