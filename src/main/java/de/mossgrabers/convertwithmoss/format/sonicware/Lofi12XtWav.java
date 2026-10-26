// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt
package de.mossgrabers.convertwithmoss.format.sonicware;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;

/** The factory Lofi-12 XT RIFF layout, including its fixed cue and tempo label. */
public final class Lofi12XtWav
{
    private static final byte [] CUE = HexFormat.of ().parseHex ("01000000010000000000000064617461000000000000000000000000");
    private static final byte [] LABEL = HexFormat.of ().parseHex ("6164746c6c61626c120000000100000054656d706f3a203030302e300000");

    private Lofi12XtWav ()
    {
        // Utility class.
    }

    /** A device slice; the end frame is inclusive, pitch is in cents. */
    public record Slice (int start, int end, int level, int choke, int pitch)
    {
    }

    /**
     * Build a factory-layout WAV from signed, little-endian 16-bit mono PCM.
     * Duration policy belongs to the creator: some factory files exceed 40 seconds and must
     * still be reproducible byte-for-byte by this low-level writer.
     */
    public static byte [] build (final int sampleRate, final byte [] pcm, final long tags, final List<Slice> slices)
    {
        if ((sampleRate != 12000 && sampleRate != 24000) || pcm.length % 2 != 0 || pcm.length == 0 || slices.size () > 16)
            throw new IllegalArgumentException ("Lofi-12 XT requires 12/24 kHz, 16-bit mono, nonempty audio and at most 16 slices.");

        final ByteBuffer fmt = littleEndian (16);
        fmt.putShort ((short) 1).putShort ((short) 1).putInt (sampleRate).putInt (sampleRate * 2).putShort ((short) 2).putShort ((short) 16);
        final ByteBuffer orsl = littleEndian (4 + slices.size () * 32);
        orsl.putInt (slices.size ());
        for (int i = 0; i < slices.size (); i++)
        {
            final Slice slice = slices.get (i);
            if (slice.start () < 0 || slice.end () < slice.start () || slice.end () >= pcm.length / 2 || slice.level () < 0 || slice.level () > 127 || slice.choke () < 0 || slice.choke () > 4 || Math.abs ((long) slice.pitch ()) > 1200)
                throw new IllegalArgumentException ("Invalid Lofi-12 XT slice.");
            orsl.putInt (i).putInt (slice.start ()).putInt (slice.end ()).putInt (slice.level ()).putInt (0).putInt (slice.pitch ()).putInt (slice.choke ()).putInt (0);
        }

        final ByteBuffer out = littleEndian (146 + slices.size () * 32 + pcm.length);
        out.put ("RIFF".getBytes (StandardCharsets.US_ASCII)).putInt (out.capacity () - 8).put ("WAVE".getBytes (StandardCharsets.US_ASCII));
        chunk (out, "fmt ", fmt.array ());
        chunk (out, "cue ", CUE);
        chunk (out, "LIST", LABEL);
        chunk (out, "ORSL", orsl.array ());
        chunk (out, "AFTG", littleEndian (8).putLong (tags).array ());
        chunk (out, "data", pcm);
        return out.array ();
    }

    private static ByteBuffer littleEndian (final int size)
    {
        return ByteBuffer.allocate (size).order (ByteOrder.LITTLE_ENDIAN);
    }

    private static void chunk (final ByteBuffer out, final String id, final byte [] data)
    {
        out.put (id.getBytes (StandardCharsets.US_ASCII)).putInt (data.length).put (data);
        if ((data.length & 1) != 0)
            out.put ((byte) 0);
    }
}
