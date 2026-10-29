package com.tonybenoy.dashboard;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * Flips one boolean resource to true inside the stored resources.arsc of an APK copy, changing nothing else
 * (plus the two zip CRC fields). Works on the aapt2 layouts seen on Android 16: compact entries and 16-bit offsets.
 * The resource is found by key name, so it survives resource id changes between Bluetooth module versions.
 */
public class BtPatcher {

    /** @return number of values set to true (0 = resource not found) */
    public static int patchBoolTrue(File in, File out, String keyName) throws Exception {
        byte[] data;
        try (RandomAccessFile f = new RandomAccessFile(in, "r")) {
            data = new byte[(int) f.length()];
            f.readFully(data);
        }
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        // --- locate resources.arsc through the zip central directory
        int eocd = -1;
        for (int i = data.length - 22; i >= Math.max(0, data.length - 65557); i--) {
            if (b.getInt(i) == 0x06054b50) { eocd = i; break; }
        }
        if (eocd < 0) throw new IllegalStateException("not a zip");
        int cdSize = b.getInt(eocd + 12), cdOff = b.getInt(eocd + 16);
        int cdPos = -1, localOff = 0, size = 0;
        for (int p = cdOff; p < cdOff + cdSize; ) {
            int nameLen = b.getShort(p + 28) & 0xFFFF, extraLen = b.getShort(p + 30) & 0xFFFF, cmtLen = b.getShort(p + 32) & 0xFFFF;
            String name = new String(data, p + 46, nameLen, StandardCharsets.UTF_8);
            if (name.equals("resources.arsc")) {
                if (b.getShort(p + 10) != 0) throw new IllegalStateException("resources.arsc is compressed");
                cdPos = p;
                size = b.getInt(p + 24);
                localOff = b.getInt(p + 42);
                break;
            }
            p += 46 + nameLen + extraLen + cmtLen;
        }
        if (cdPos < 0) throw new IllegalStateException("no resources.arsc");
        int start = localOff + 30 + (b.getShort(localOff + 26) & 0xFFFF) + (b.getShort(localOff + 28) & 0xFFFF);

        // --- find the resource-table package and its key string pool
        if (u16(b, start) != 0x0002) throw new IllegalStateException("not a resource table");
        int tableEnd = start + (int) u32(b, start + 4);
        int pkg = -1;
        for (int p = start + u16(b, start + 2); p + 8 <= tableEnd; ) {
            int type = u16(b, p);
            long sz = u32(b, p + 4);
            if (sz == 0) break;
            if (type == 0x0200) { pkg = p; break; }
            p += (int) sz;
        }
        if (pkg < 0) throw new IllegalStateException("no package");
        int pkgHdr = u16(b, pkg + 2), pkgEnd = pkg + (int) u32(b, pkg + 4);
        int keyPool = pkg + (int) u32(b, pkg + 8 + 4 + 256 + 4 + 4);   // keyStrings offset field
        int keyIndex = indexInPool(data, b, keyPool, keyName);
        if (keyIndex < 0) return 0;

        // --- patch every matching boolean entry in every type chunk
        int patched = 0;
        for (int p = pkg + pkgHdr; p + 8 <= pkgEnd; ) {
            int type = u16(b, p), hsize = u16(b, p + 2);
            long sz = u32(b, p + 4);
            if (sz == 0) break;
            if (type == 0x0201) {
                int flags = data[p + 9] & 0xFF;
                int count = (int) u32(b, p + 12);
                int entriesStart = (int) u32(b, p + 16);
                if ((flags & 0x01) == 0) {                                  // not sparse
                    for (int i = 0; i < count; i++) {
                        long eoff;
                        if ((flags & 0x02) != 0) { int raw = u16(b, p + hsize + 2 * i); eoff = raw == 0xFFFF ? -1 : raw * 4L; }
                        else { eoff = u32(b, p + hsize + 4 * i); if (eoff == 0xFFFFFFFFL) eoff = -1; }
                        if (eoff < 0) continue;
                        int e = p + entriesStart + (int) eoff;
                        int eflags = u16(b, e + 2), key, dtype, dpos;
                        if ((eflags & 0x0008) != 0) { key = u16(b, e); dtype = eflags >> 8; dpos = e + 4; }   // compact
                        else { key = (int) u32(b, e + 4); int v = e + u16(b, e); dtype = data[v + 3] & 0xFF; dpos = v + 4; }
                        if (key == keyIndex && dtype == 0x12) { b.putInt(dpos, -1); patched++; }
                    }
                }
            }
            p += (int) sz;
        }
        if (patched == 0) return 0;

        // --- keep the zip consistent
        CRC32 crc = new CRC32();
        crc.update(data, start, size);
        b.putInt(localOff + 14, (int) crc.getValue());
        b.putInt(cdPos + 16, (int) crc.getValue());
        try (FileOutputStream o = new FileOutputStream(out)) { o.write(data); }
        return patched;
    }

    private static int indexInPool(byte[] d, ByteBuffer b, int pool, String name) {
        int count = (int) u32(b, pool + 8), flags = (int) u32(b, pool + 16);
        int strStart = pool + (int) u32(b, pool + 20), offs = pool + u16(b, pool + 2);
        boolean utf8 = (flags & 0x100) != 0;
        byte[] want = name.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < count; i++) {
            int s = strStart + (int) u32(b, offs + 4 * i);
            if (utf8) {
                int l = d[s] & 0xFF; s += (l & 0x80) != 0 ? 2 : 1;             // char length (skip)
                int bl = d[s] & 0xFF;
                if ((bl & 0x80) != 0) { bl = ((bl & 0x7F) << 8) | (d[s + 1] & 0xFF); s += 2; } else s += 1;
                if (bl == want.length) {
                    boolean eq = true;
                    for (int k = 0; k < bl; k++) if (d[s + k] != want[k]) { eq = false; break; }
                    if (eq) return i;
                }
            }
        }
        return -1;
    }

    private static int u16(ByteBuffer b, int o) { return b.getShort(o) & 0xFFFF; }
    private static long u32(ByteBuffer b, int o) { return b.getInt(o) & 0xFFFFFFFFL; }

    /** Test harness: java BtPatcher in.apk out.apk */
    public static void main(String[] a) throws Exception {
        System.out.println("patched=" + patchBoolTrue(new File(a[0]), new File(a[1]), "a2dp_sink_automatically_request_audio_focus"));
    }
}
