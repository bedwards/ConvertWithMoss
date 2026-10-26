// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt
package de.mossgrabers.convertwithmoss.format.sonicware;

import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.mossgrabers.convertwithmoss.core.INotifier;
import de.mossgrabers.convertwithmoss.core.ConverterBackend;
import de.mossgrabers.convertwithmoss.core.detector.DefaultMultisampleSource;
import de.mossgrabers.convertwithmoss.core.model.ISampleZone;
import de.mossgrabers.convertwithmoss.core.model.implementation.DefaultGroup;
import de.mossgrabers.convertwithmoss.core.model.implementation.DefaultSampleZone;
import de.mossgrabers.convertwithmoss.file.wav.WaveFile;
import de.mossgrabers.convertwithmoss.format.wav.WavFileSampleData;

/** Standalone regression suite; run with target/test-classes:target/classes:target/lib/*. */
public class Lofi12XtTest
{
    private static final INotifier NOTIFIER = (INotifier) Proxy.newProxyInstance (INotifier.class.getClassLoader (), new Class<?> [] { INotifier.class }, (proxy, method, arguments) -> null);

    public static void main (final String [] args) throws Exception
    {
        final Path root = Path.of (args[0]);
        check (!Files.exists (root), "Use a fresh output directory");
        check (new ConverterBackend (NOTIFIER).getCreators ().stream ().anyMatch (c -> c.getName ().equals ("Sonicware Lofi-12 XT")), "Destination registration");
        testTags ();
        testOptions ();
        testWriter ();
        testCreator (root.resolve ("24k"), 24000, false);
        testCreator (root.resolve ("12k"), 12000, true);
        testLimits (root.resolve ("limits"));
        if (args.length > 1)
            factoryProof (Path.of (args[1]));
        System.out.println ("Lofi12Xt regression checks passed; output: " + root);
    }

    private static void testTags ()
    {
        final String [] categories = { "Kick", "Synth", "Snare", "Strings", "Hi-Hat", "Winds", "Drum", "Vocal", "Percussion", "FX", "Bass", "Loops", "Guitar", "Slice", "Piano" };
        for (int bit = 0; bit < categories.length; bit++)
            check (Lofi12XtCreator.categoryTags (categories[bit]) == 1L << bit, "Category " + categories[bit]);
        check (Lofi12XtCreator.categoryTags ("Unknown") == 0 && Lofi12XtCreator.categoryTags (null) == 0, "Unknown tags");
    }

    private static void testOptions ()
    {
        final Lofi12XtCreatorUI settings = new Lofi12XtCreatorUI ();
        check (settings.getSampleRate () == 24000 && !settings.isAllZones (), "Defaults");
        check (!settings.checkSettingsCLI (NOTIFIER, new HashMap<> (Map.of ("Lofi12XtSampleRate", "44100"))), "Reject rate");
        check (!settings.checkSettingsCLI (NOTIFIER, new HashMap<> (Map.of ("Lofi12XtNotes", "bad"))), "Reject notes");
        check (settings.checkSettingsCLI (NOTIFIER, new HashMap<> (Map.of ("Lofi12XtSampleRate", "12000", "Lofi12XtNotes", "all"))), "Valid options");
        check (settings.getSampleRate () == 12000 && settings.isAllZones (), "Parsed options");
        check (settings.checkSettingsCLI (NOTIFIER, new HashMap<> ()) && settings.getSampleRate () == 24000 && !settings.isAllZones (), "Reset defaults");
    }

    private static void testWriter ()
    {
        final byte [] pcm = new byte [100];
        final var slices = List.of (new Lofi12XtWav.Slice (0, 49, 100, 1, -75));
        final byte [] bytes = Lofi12XtWav.build (12000, pcm, 0x8000000000008001L, slices);
        final var chunks = chunks (bytes);
        check (new ArrayList<> (chunks.keySet ()).equals (List.of ("fmt ", "cue ", "LIST", "ORSL", "AFTG", "data")), "Chunk order");
        check (chunks.get ("LIST").length == 30 && chunks.get ("cue ").length == 28, "Fixed chunk lengths");
        check (buffer (chunks.get ("AFTG")).getLong () == 0x8000000000008001L, "64-bit tag mask");
        check (buffer (chunks.get ("ORSL")).getInt (24) == -75, "Signed slice pitch");
        try
        {
            Lofi12XtWav.build (12000, pcm, 0, List.of (new Lofi12XtWav.Slice (0, 50, 100, 0, 0)));
            throw new AssertionError ("Out-of-range slice accepted");
        }
        catch (final IllegalArgumentException expected)
        {
            // Expected.
        }
    }

    private static void testCreator (final Path root, final int rate, final boolean all) throws Exception
    {
        // Constant stereo: the expected mono average is exactly 2000 at either rate.
        final ByteBuffer audio = ByteBuffer.allocate (48000 * 4).order (ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 48000; i++)
            audio.putShort ((short) 1000).putShort ((short) 3000);
        final WavFileSampleData sample = new WavFileSampleData (new WaveFile (2, 48000, 16, audio.array ()));
        final List<ISampleZone> zones = new ArrayList<> ();
        for (final int key: new int [] { 60, 61, 72, 60, -1 })
        {
            final DefaultSampleZone zone = new DefaultSampleZone ("zone", sample);
            zone.setKeyRoot (key);
            zone.setStart (12000);
            zone.setStop (36000);
            zones.add (zone);
        }
        final String name = "Épic / Piano:_" + "long-name-".repeat (12);
        final DefaultMultisampleSource source = new DefaultMultisampleSource (new File ("fixture"), new String [] { "fixture" }, name);
        source.setGroups (List.of (new DefaultGroup (zones)));
        source.getMetadata ().setCategory ("Piano");
        final Lofi12XtCreator creator = new Lofi12XtCreator (NOTIFIER);
        creator.getSettings ().checkSettingsCLI (NOTIFIER, new HashMap<> (Map.of ("Lofi12XtSampleRate", Integer.toString (rate), "Lofi12XtNotes", all ? "all" : "c")));
        check (!creator.preservesSourceFolderStructure (), "Fixed device tree");
        creator.createPreset (root.toFile (), source);
        creator.createPreset (root.toFile (), source); // Existing exports must never be overwritten.
        try (var paths = Files.walk (root))
        {
            final List<Path> wavs = paths.filter (p -> p.toString ().endsWith (".wav")).toList ();
            check (wavs.size () == (all ? 10 : 6), "One WAV per selected zone, including duplicates");
            for (final Path path: wavs)
            {
                check (path.getFileName ().toString ().length () <= 64, "Filename length");
                check (path.getFileName ().toString ().matches ("[a-z0-9-]+_[a-z0-9-]+\\.wav"), "Safe filename");
                final var parts = chunks (Files.readAllBytes (path));
                final ByteBuffer fmt = buffer (parts.get ("fmt "));
                check (fmt.getShort (0) == 1 && fmt.getShort (2) == 1 && fmt.getInt (4) == rate && fmt.getShort (14) == 16, "PCM format");
                final byte [] pcm = parts.get ("data");
                check (pcm.length == rate, "Trim and resampling duration");
                check (Math.abs (buffer (pcm).getShort (pcm.length / 2 & ~1) - 2000) <= 1, "Stereo average");
                check (buffer (parts.get ("AFTG")).getLong () == 1L << 14, "Keyboard tag");
                check (parts.get ("ORSL").length == 4 && buffer (parts.get ("ORSL")).getInt () == 0, "Unsliced zone");
            }
        }
    }

    private static void testLimits (final Path root) throws Exception
    {
        final DefaultMultisampleSource source = new DefaultMultisampleSource (new File ("fixture"), new String [] { "fixture" }, "too-long");
        final DefaultSampleZone zone = new DefaultSampleZone ("long", new WavFileSampleData (new WaveFile (1, 24000, 16, 24000 * 41)));
        zone.setKeyRoot (60);
        source.setGroups (List.of (new DefaultGroup (List.of (zone))));
        try
        {
            new Lofi12XtCreator (NOTIFIER).createPreset (root.toFile (), source);
            throw new AssertionError ("Overlong sample accepted");
        }
        catch (final java.io.IOException expected)
        {
            check (expected.getMessage ().contains ("40 seconds"), "Duration error");
        }
    }

    private static void factoryProof (final Path root) throws Exception
    {
        int count = 0;
        try (var paths = Files.walk (root))
        {
            for (final Path path: paths.filter (p -> p.toString ().toLowerCase ().endsWith (".wav")).toList ())
            {
                final byte [] original = Files.readAllBytes (path);
                final var parts = chunks (original);
                final ByteBuffer orsl = buffer (parts.get ("ORSL"));
                final int n = orsl.getInt ();
                final List<Lofi12XtWav.Slice> slices = new ArrayList<> ();
                for (int i = 0; i < n; i++)
                {
                    final int offset = 4 + i * 32;
                    slices.add (new Lofi12XtWav.Slice (orsl.getInt (offset + 4), orsl.getInt (offset + 8), orsl.getInt (offset + 12), orsl.getInt (offset + 24), orsl.getInt (offset + 20)));
                }
                final byte [] rebuilt = Lofi12XtWav.build (buffer (parts.get ("fmt ")).getInt (4), parts.get ("data"), buffer (parts.get ("AFTG")).getLong (), slices);
                check (Arrays.equals (original, rebuilt), "Factory byte equality: " + path);
                count++;
            }
        }
        check (count > 0, "Factory fixtures found");
        System.out.println ("Factory WAVs reproduced byte-for-byte: " + count);
    }

    private static Map<String, byte []> chunks (final byte [] bytes)
    {
        final ByteBuffer input = buffer (bytes);
        check (input.getInt (4) == bytes.length - 8, "RIFF size");
        final Map<String, byte []> parts = new LinkedHashMap<> ();
        for (int at = 12; at < bytes.length;)
        {
            final String id = new String (bytes, at, 4, StandardCharsets.US_ASCII);
            final int size = input.getInt (at + 4);
            check (at + 8 + size <= bytes.length, "Chunk bounds");
            parts.put (id, Arrays.copyOfRange (bytes, at + 8, at + 8 + size));
            at += 8 + size + (size & 1);
        }
        return parts;
    }

    private static ByteBuffer buffer (final byte [] bytes)
    {
        return ByteBuffer.wrap (bytes).order (ByteOrder.LITTLE_ENDIAN);
    }

    private static void check (final boolean ok, final String message)
    {
        if (!ok)
            throw new AssertionError (message);
    }
}
