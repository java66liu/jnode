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
 
package org.jnode.fs.ntfs;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.jnode.fs.ntfs.attribute.AttributeListAttribute;
import org.jnode.fs.ntfs.attribute.AttributeListEntry;
import org.jnode.fs.ntfs.attribute.NTFSAttribute;
import org.jnode.fs.ntfs.attribute.NTFSNonResidentAttribute;
import org.jnode.fs.ntfs.attribute.NTFSResidentAttribute;
import org.jnode.util.NumberUtils;

/**
 * MFT file record structure.
 *
 * @author Chira
 * @author Ewout Prangsma (epr@users.sourceforge.net)
 * @author Daniel Noll (daniel@noll.id.au) (new attribute iteration support)
 */
public class FileRecord extends NTFSRecord {

    /**
     * Index of the file record within the MFT.
     */
    private long referenceNumber;

    /**
     * Cached attribute list attribute.
     */
    protected AttributeListAttribute attributeListAttribute;

    /**
     * A cached copy of the attributes.
     */
    protected List<NTFSAttribute> attributeList;

    /**
     * Cached standard information attribute.
     */
    private StandardInformationAttribute standardInformationAttribute;

    /**
     * Cached file name attribute.
     */
    private FileNameAttribute fileNameAttribute;

    /**
     * Initialize this instance.
     *
     * @param volume          reference to the NTFS volume.
     * @param referenceNumber the reference number of the file within the MFT.
     * @param buffer          data buffer.
     * @param offset          offset into the buffer.
     */
    public FileRecord(NTFSVolume volume, long referenceNumber, byte[] buffer, int offset) throws IOException {
        super(volume, buffer, offset);
        this.referenceNumber = referenceNumber;

        // Linux NTFS docs say there can only be one of these, so I'll believe them.
        attributeListAttribute = (AttributeListAttribute) findStoredAttributeByType(NTFSAttribute.Types.ATTRIBUTE_LIST);
    }


    /**
     * Checks if the record appears to be valid.
     *
     * @throws IOException if an error occurs.
     */
    public void checkIfValid() throws IOException {
        // check for the magic number to see if we have a filerecord
        if (getMagic() != Magic.FILE) {
            log.debug("Invalid magic number found for FILE record: " + getMagic() + " -- dumping buffer");
            for (int off = 0; off < getBuffer().length; off += 32) {
                StringBuilder builder = new StringBuilder();
                for (int i = off; i < off + 32 && i < getBuffer().length; i++) {
                    String hex = Integer.toHexString(getBuffer()[i]);
                    while (hex.length() < 2) {
                        hex = '0' + hex;
                    }

                    builder.append(' ').append(hex);
                }
                log.debug(builder.toString());
            }

            throw new IOException("Invalid magic found: " + getMagic());
        }

        // This additional sanity check is possible if the record also contains the MFT number.
        // Helps catch bugs where a record is being read from the wrong offset.
        final long storedReferenceNumber = getStoredReferenceNumber();
        if (storedReferenceNumber >= 0 && referenceNumber != storedReferenceNumber) {
            throw new IOException("Stored reference number " + getStoredReferenceNumber()
                + " does not match reference number " + referenceNumber);
        }
    }

    /**
     * Gets the allocated size of the FILE record in bytes.
     *
     * @return Returns the allocated size.
     */
    public long getAllocatedSize() {
        return getUInt32(0x1C);
    }

    /**
     * Gets the reference number of the base record. For continuation MFT entries this will reference the main record.
     * For main records this should match {@link #referenceNumber}.
     *
     * @return Returns the base reference number.
     */
    public long getBaseReferenceNumber() {
        return getUInt48(0x20);
    }

    /**
     * Gets the real size of the FILE record in bytes.
     *
     * @return Returns the realSize.
     */
    public long getRealSize() {
        return getUInt32(0x18);
    }

    /**
     * Is this record in use?
     *
     * @return {@code true} if the record is in use.
     */
    public boolean isInUse() {
        return (getFlags() & 0x01) != 0;
    }

    /**
     * Is this a directory?
     *
     * @return {@code true} if the record is a directory.
     */
    public boolean isDirectory() {
        return (getFlags() & 0x02) != 0;
    }

    /**
     * Gets the hard link count.
     *
     * @return Returns the hardLinkCount.
     */
    public int getHardLinkCount() {
        return getUInt16(0x12);
    }

    /**
     * Gets the byte offset to the first attribute in this mft record from the start of the mft record.
     *
     * @return the first attribute offset.
     */
    public int getFirstAttributeOffset() {
        return getUInt16(0x14);
    }

    /**
     * Gets the flags.
     *
     * @return Returns the flags.
     */
    public int getFlags() {
        return getUInt16(0x16);
    }

    /**
     * Gets the Next Attribute Id.
     *
     * @return Returns the nextAttributeID.
     */
    public int getNextAttributeID() {
        return getUInt16(0x28);
    }

    /**
     * Gets the number of times this mft record has been reused.
     *
     * @return Returns the sequenceNumber.
     */
    public int getSequenceNumber() {
        return getUInt16(0x10);
    }

    /**
     * Gets the reference number of this record within the MFT. This value is not actually stored in the record, but
     * passed in from the outside.
     *
     * @return the reference number.
     */
    public long getReferenceNumber() {
        return referenceNumber;
    }

    /**
     * @return Returns the updateSequenceOffset.
     */
    public int getUpdateSequenceOffset() {
        return getUInt16(0x4);
    }

    /**
     * Gets the stored reference number. This can be compared against the reference number to confirm that the correct
     * file record was returned, however it is not available on all versions of NTFS, and even on recent versions some
     * MFT records lack it.
     *
     * @return the stored file reference number, or {@code -1} if it is not stored.
     */
    public long getStoredReferenceNumber() {
        // Expected to be 0x2A pre-XP.
        if (getUpdateSequenceOffset() >= 0x30) {
            return getUInt32(0x2C);
        } else {
            return -1;
        }
    }

    /**
     * Gets the name of this file.
     *
     * @return the filename.
     */
    public String getFileName() {
        final FileNameAttribute fnAttr = getFileNameAttribute();
        if (fnAttr != null) {
            return fnAttr.getFileName();
        } else {
            return null;
        }
    }

    /**
     * Gets the standard information attribute for this file record.
     *
     * @return the standard information attribute.
     */
    public StandardInformationAttribute getStandardInformationAttribute() {
        if (standardInformationAttribute == null) {
            standardInformationAttribute =
                (StandardInformationAttribute) findAttributeByType(NTFSAttribute.Types.STANDARD_INFORMATION);
        }
        return standardInformationAttribute;
    }

    /**
     * Gets the file name attribute for this file record.
     *
     * @return the file name attribute.
     */
    public FileNameAttribute getFileNameAttribute() {
        if (fileNameAttribute == null) {
            AttributeIterator iterator = findAttributesByType(NTFSAttribute.Types.FILE_NAME);
            NTFSAttribute attribute = iterator.next();

            // Search for a Win32 file name if possible
            while (attribute != null) {
                if (fileNameAttribute == null ||
                    fileNameAttribute.getNameSpace() != FileNameAttribute.NameSpace.WIN32) {
                    fileNameAttribute = (FileNameAttribute) attribute;
                }

                attribute = iterator.next();
            }
        }
        return fileNameAttribute;
    }

    /**
     * Gets the attributes stored in this file record.
     *
     * @return an iterator over attributes stored in this file record.
     */
    public AttributeIterator getAllStoredAttributes() {
        return new StoredAttributeIterator();
    }

    /**
     * Finds a single stored attribute by ID.
     *
     * @param id the ID.
     * @return the attribute found, or {@code null} if not found.
     */
    private NTFSAttribute findStoredAttributeByID(int id) {
        AttributeIterator iter = getAllStoredAttributes();
        NTFSAttribute attr;
        while ((attr = iter.next()) != null) {
            if (attr.getAttributeID() == id) {
                return attr;
            }
        }
        return null;
    }

    /**
     * Finds a single stored attribute by type.
     *
     * @param typeID the type ID
     * @return the attribute found, or {@code null} if not found.
     * @see NTFSAttribute.Types
     */
    public NTFSAttribute findStoredAttributeByType(int typeID) {
        AttributeIterator iter = getAllStoredAttributes();
        NTFSAttribute attr;
        while ((attr = iter.next()) != null) {
            if (attr.getAttributeType() == typeID) {
                return attr;
            }
        }
        return null;
    }

    /**
     * Gets a collection of all attributes in this file record, including any attributes
     * which are stored in other file records referenced from an $ATTRIBUTE_LIST attribute.
     *
     * @return a collection of all attributes.
     */
    private synchronized List<NTFSAttribute> getAllAttributes() {
        if (attributeList == null) {
            attributeList = new ArrayList<NTFSAttribute>();

            try {
                AttributeIterator iter;
                if (attributeListAttribute == null) {
                    log.debug("All attributes stored");
                    iter = getAllStoredAttributes();
                } else {
                    log.debug("Attributes in attribute list");
                    iter = new AttributeListAttributeIterator();
                }

                NTFSAttribute attr;
                while ((attr = iter.next()) != null) {
                    attributeList.add(attr);
                }
            } catch (Exception e) {
                log.error("Error getting attributes for entry: " + this, e);
            }
        }

        return attributeList;
    }

    /**
     * Gets the first attribute in this filerecord with a given type.
     *
     * @param attrTypeID the type ID of the attribute we're looking for.
     * @return the attribute.
     */
    public NTFSAttribute findAttributeByType(int attrTypeID) {
        log.debug("findAttributeByType(0x" + NumberUtils.hex(attrTypeID, 4) + ")");

        for (NTFSAttribute attr : getAllAttributes()) {
            if (attr.getAttributeType() == attrTypeID) {
                log.debug("findAttributeByType(0x" + NumberUtils.hex(attrTypeID, 4) + ") found");
                return attr;
            }
        }

        log.debug("findAttributeByType(0x" + NumberUtils.hex(attrTypeID, 4) + ") not found");
        return null;
    }

    /**
     * Gets attributes in this filerecord with a given type.
     *
     * @param attrTypeID the type ID of the attribute we're looking for.
     * @return the attributes, will be empty if not found, never {@code null}.
     */
    public AttributeIterator findAttributesByType(final int attrTypeID) {
        log.debug("findAttributesByType(0x" + NumberUtils.hex(attrTypeID, 4) + ")");

        return new FilteredAttributeIterator(getAllAttributes().iterator()) {
            @Override
            protected boolean matches(NTFSAttribute attr) {
                return attr.getAttributeType() == attrTypeID;
            }
        };
    }

    /**
     * Gets attributes in this filerecord with a given type and name.
     *
     * @param attrTypeID the type ID of the attribute we're looking for.
     * @param name       the name to look for.
     * @return the attributes, will be empty if not found, never {@code null}.
     */
    public AttributeIterator findAttributesByTypeAndName(final int attrTypeID, final String name) {
        log.debug("findAttributesByTypeAndName(0x" + NumberUtils.hex(attrTypeID, 4) + "," + name + ")");
        return new FilteredAttributeIterator(getAllAttributes().iterator()) {
            @Override
            protected boolean matches(NTFSAttribute attr) {
                if (attr.getAttributeType() == attrTypeID) {
                    String attrName = attr.getAttributeName();
                    if (name == null ? attrName == null : name.equals(attrName)) {
                        log.debug("findAttributesByTypeAndName(0x" + NumberUtils.hex(attrTypeID, 4) + "," + name
                            + ") found");
                        return true;
                    }
                }
                return false;
            }
        };
    }

    /**
     * Gets the total size used for the given attribute.
     *
     * @param attrTypeID the type of attribute to get the size for, e.g. {@link NTFSAttribute.Types#DATA}.
     * @param name       the name of the attribute or {@code null} for no name.
     * @return the total size of the attribute.
     */
    public long getAttributeTotalSize(int attrTypeID, String name) {
        FileRecord.AttributeIterator attributes = findAttributesByTypeAndName(attrTypeID, name);
        NTFSAttribute attribute = attributes.next();

        if (attribute == null) {
            throw new IllegalStateException("Failed to find an attribute with type: " + attrTypeID + " and name: '" +
                name + "'");
        }

        long totalSize = 0;

        while (attribute != null) {
            if (attribute.isResident()) {
                totalSize += ((NTFSResidentAttribute) attribute).getAttributeLength();
            } else {
                totalSize += ((NTFSNonResidentAttribute) attribute).getAttributeActualSize();
            }

            attribute = attributes.next();
        }

        return totalSize;
    }

    /**
     * Reads data from the file.
     *
     * @param fileOffset the offset into the file.
     * @param dest       the destination byte array into which to copy the file data.
     * @param off        the offset into the destination byte array.
     * @param len        the number of bytes of data to read.
     * @throws IOException if an error occurs reading from the filesystem.
     */
    public void readData(long fileOffset, byte[] dest, int off, int len) throws IOException {
        // Explicitly look for the attribute with no name, to avoid getting alternate streams.
        readData(null, fileOffset, dest, off, len);
    }

    /**
     * Reads data from the file.
     *
     * @param fileOffset the offset into the file.
     * @param dest       the destination byte array into which to copy the file data.
     * @param off        the offset into the destination byte array.
     * @param len        the number of bytes of data to read.
     * @throws IOException if an error occurs reading from the filesystem.
     */
    public void readData(String streamName, long fileOffset, byte[] dest, int off, int len) throws IOException {
        if (log.isDebugEnabled()) {
            log.debug("readData: offset " + fileOffset + " stream: " + streamName + " length " + len +
                ", file record = " + this);
        }

        if (len == 0) {
            return;
        }

        final AttributeIterator dataAttrs = findAttributesByTypeAndName(NTFSAttribute.Types.DATA, streamName);
        NTFSAttribute attr = dataAttrs.next();
        if (attr == null) {
            throw new IOException("Data attribute not found, file record = " + this);
        }

        if (attr.isResident()) {
            if (dataAttrs.next() != null) {
                throw new IOException("Resident attribute should be by itself, file record = " + this);
            }

            final NTFSResidentAttribute resData = (NTFSResidentAttribute) attr;
            final int attrLength = resData.getAttributeLength();
            if (attrLength < len) {
                throw new IOException("File data(" + attrLength + "b) is not large enough to read:" + len + "b");
            }
            resData.getData(resData.getAttributeOffset() + (int) fileOffset, dest, off, len);

            if (log.isDebugEnabled()) {
                log.debug("readData: read from resident data");
            }

            return;
        }

        // At this point we know that at least the first attribute is non-resident.

        // calculate start and end cluster
        final int clusterSize = getVolume().getClusterSize();
        final long startCluster = fileOffset / clusterSize;
        final long endCluster = (fileOffset + len - 1) / clusterSize;
        final int nrClusters = (int) (endCluster - startCluster + 1);
        final byte[] tmp = new byte[nrClusters * clusterSize];

        long clusterWithinNresData = startCluster;
        int readClusters = 0;
        do {
            if (attr.isResident()) {
                throw new IOException("Resident attribute should be by itself, file record = " + this);
            }

            final NTFSNonResidentAttribute nresData = (NTFSNonResidentAttribute) attr;

            readClusters += nresData.readVCN(clusterWithinNresData, tmp, 0, nrClusters);
            if (readClusters == nrClusters) {
                // Already done.
                break;
            }

            // When there are multiple attributes, the data in each one claims to start at VCN 0.
            // Clearly this is not the case, so we need to offset when we read.
            clusterWithinNresData -= nresData.getNumberOfVCNs();
            attr = dataAttrs.next();
        } while (attr != null);

        if (log.isDebugEnabled()) {
            log.debug("readData: read " + readClusters + " from non-resident attributes");
        }

        if (readClusters != nrClusters) {
            throw new IOException("Requested " + nrClusters + " clusters but only read " + readClusters +
                ", offset = " + off + ", file record = " + this);
        }

        System.arraycopy(tmp, (int) fileOffset % clusterSize, dest, off, len);
    }

    @Override
    public String toString() {
        if (isInUse()) {
            return super.toString() + "[fileName=" + getFileName() + "]";
        } else {
            return super.toString() + "[unused]";
        }
    }

    /**
     * Iterator over multiple attributes, where those attributes are stored in an attribute list instead of directly in
     * the file record.
     */
    private class AttributeListAttributeIterator extends AttributeIterator {

        /**
         * Current iterator over attribute list entries.
         */
        private Iterator<AttributeListEntry> entryIterator;

        /**
         * Constructs the iterator.
         */
        private AttributeListAttributeIterator() {
            try {
                entryIterator = attributeListAttribute.getAllEntries();
            } catch (IOException e) {
                log.error("Error getting attributes from attribute list, file record " + FileRecord.this, e);
                List<AttributeListEntry> emptyList = Collections.emptyList();
                entryIterator = emptyList.iterator();
            }
        }

        @Override
        public NTFSAttribute next() {
            Set<Integer> encounteredIds = new HashSet<Integer>();

            while (entryIterator.hasNext()) {
                AttributeListEntry entry = entryIterator.next();

                // Sanity check - ensure we don't hit the same ID more than once
                int attributeId = entry.getAttributeID();
                if (encounteredIds.contains(attributeId)) {
                    throw new IllegalStateException("Hit the same attribute ID more than once, aborting. ref = 0x" +
                        Long.toHexString(entry.getFileReferenceNumber()) + " id=" + attributeId);
                }

                encounteredIds.add(attributeId);

                try {
                    // If it's resident (i.e. in the current file record) then we don't need to
                    // look it up, and doing so would risk infinite recursion.
                    FileRecord holdingRecord;
                    if (entry.getFileReferenceNumber() == referenceNumber) {
                        holdingRecord = FileRecord.this;
                    } else {
                        log.debug("Looking up MFT entry for: " + entry.getFileReferenceNumber());
                        holdingRecord = getVolume().getMFT().getRecord(entry.getFileReferenceNumber());
                    }

                    NTFSAttribute attribute = holdingRecord.findStoredAttributeByID(entry.getAttributeID());
                    log.debug("Attribute: " + attribute);
                    return attribute;
                } catch (IOException e) {
                    throw new IllegalStateException("Error getting MFT or FileRecord for attribute in list, ref = 0x" +
                        Long.toHexString(entry.getFileReferenceNumber()), e);
                }
            }

            return null;
        }
    }

    /**
     * Iterator over stored attributes in this file record.
     */
    private class StoredAttributeIterator extends AttributeIterator {
        /**
         * The next attribute offset to look at.
         */
        private int nextOffset = getFirstAttributeOffset();

        @Override
        public NTFSAttribute next() {
            final int offset = nextOffset;
            final int type = getUInt32AsInt(offset + 0x00);
            if (type == 0xFFFFFFFF) {
                // Normal end of list condition.
                return null;
            } else {
                NTFSAttribute attribute = NTFSAttribute.getAttribute(FileRecord.this, offset);
                log.debug("Attribute: " + attribute);
                int offsetToNextOffset = getUInt32AsInt(offset + 0x04);
                if (offsetToNextOffset <= 0) {
                    log.error("Non-positive offset, preventing infinite loop.  Data on disk may be corrupt.  "
                        + "referenceNumber = " + referenceNumber);
                    return null;
                }

                nextOffset += offsetToNextOffset;
                return attribute;
            }
        }
    }

    /**
     * An iterator for filtering another iterator.
     */
    private abstract class FilteredAttributeIterator extends AttributeIterator {
        private Iterator<NTFSAttribute> attributes;

        private FilteredAttributeIterator(Iterator<NTFSAttribute> attributes) {
            this.attributes = attributes;
        }

        @Override
        public NTFSAttribute next() {
            while (attributes.hasNext()) {
                NTFSAttribute attr = attributes.next();
                if (matches(attr)) {
                    return attr;
                }
            }
            return null;
        }

        /**
         * Implemented by subclasses to perform matching logic.
         *
         * @param attr the attribute.
         * @return {@code true} if it matches, {@code false} otherwise.
         */
        protected abstract boolean matches(NTFSAttribute attr);
    }

    /**
     * Holds code common to both types of attribute list.
     */
    public abstract class AttributeIterator {
        /**
         * Gets the next element from the iterator.
         *
         * @return the next element from the iterator.  Returns {@code null} at the end.
         */
        public abstract NTFSAttribute next();
    }
}
