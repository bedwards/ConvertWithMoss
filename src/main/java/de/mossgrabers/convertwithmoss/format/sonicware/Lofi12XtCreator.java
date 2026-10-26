// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt
package de.mossgrabers.convertwithmoss.format.sonicware;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

import de.mossgrabers.convertwithmoss.core.IMultisampleSource;
import de.mossgrabers.convertwithmoss.core.INotifier;
import de.mossgrabers.convertwithmoss.core.creator.AbstractCreator;
import de.mossgrabers.convertwithmoss.core.creator.DestinationAudioFormat;
import de.mossgrabers.convertwithmoss.core.model.ISampleData;
import de.mossgrabers.convertwithmoss.core.model.ISampleZone;
import de.mossgrabers.convertwithmoss.file.AudioFileUtils;
import de.mossgrabers.convertwithmoss.file.wav.WaveFile;
import de.mossgrabers.convertwithmoss.format.TagDetector;

/** Export independent samples to the Lofi-12 XT audio pool. */
public class Lofi12XtCreator extends AbstractCreator<Lofi12XtCreatorUI>
{
    private static final String [] NOTES = { "c", "cs", "d", "ds", "e", "f", "fs", "g", "gs", "a", "as", "b" };

    public Lofi12XtCreator (final INotifier notifier)
    {
        super ("Sonicware Lofi-12 XT", "Lofi12Xt", notifier, new Lofi12XtCreatorUI ());
        this.fileEndings = new String [] { ".wav" };
    }

    @Override
    public boolean preservesSourceFolderStructure ()
    {
        return false;
    }

    @Override
    public void createPreset (final File destinationFolder, final IMultisampleSource source) throws IOException
    {
        final List<ISampleZone> zones = source.getGroups ().stream ().flatMap (group -> group.getSampleZones ().stream ()).filter (zone -> this.settingsConfiguration.isAllZones () || zone.getKeyRoot () >= 0 && zone.getKeyRoot () <= 127 && zone.getKeyRoot () % 12 == 0).toList ();
        if (zones.isEmpty ())
        {
            this.notifier.log ("IDS_LOFI12XT_NO_ZONES", source.getName ());
            return;
        }
        if (zones.size () > 1024)
            throw new IOException ("Lofi-12 XT allows at most 1024 zones in a multisample folder: " + source.getName ());

        final Path pool = destinationFolder.toPath ().resolve ("Lofi-12XT/System/Pool/Audio/kontakt");
        Files.createDirectories (pool);
        try (var entries = Files.list (pool))
        {
            if (entries.count () >= 1024)
                throw new IOException ("Lofi-12 XT kontakt folder already contains 1024 items.");
        }
        final String name = kebab (source.getName ());
        final Path folder = uniquePath (pool, name, "", 48);
        Files.createDirectory (folder);
        final int rate = this.settingsConfiguration.getSampleRate ();
        final DestinationAudioFormat format = new DestinationAudioFormat (new int [] { 16 }, rate, true);
        final long tags = categoryTags (source.getMetadata ().getCategory ());

        for (final ISampleZone zone: zones)
        {
            if (this.isCancelled ())
                return;
            final ISampleData data = zone.getSampleData ().orElseThrow (() -> new IOException ("Missing sample data: " + zone.getName ()));
            this.logResampling (zone, format);
            final WaveFile wav = AudioFileUtils.convertToWav (data, format);
            final int channels = wav.getFormatChunk ().getNumberOfChannels ();
            if (channels < 1 || wav.getFormatChunk ().getSignificantBitsPerSample () != 16)
                throw new IOException ("Cannot convert sample to 16-bit PCM: " + zone.getName ());
            final byte [] pcm = wav.getDataChunk ().getData ();
            final int frames = pcm.length / (channels * 2);
            final double ratio = (double) rate / data.getAudioMetadata ().getSampleRate ();
            final int start = (int) Math.clamp (Math.round (Math.max (0, zone.getStart ()) * ratio), 0, frames);
            // Match the shared sample trimmer: stop is the exclusive playback boundary.
            final int stop = zone.getStop () <= 0 ? frames : (int) Math.clamp (Math.round (zone.getStop () * ratio), start, frames);
            final int count = stop - start;
            if (count <= 0 || count > rate * 40)
                throw new IOException ("Lofi-12 XT sample must be nonempty and at most 40 seconds: " + zone.getName ());

            final ByteBuffer input = ByteBuffer.wrap (pcm).order (ByteOrder.LITTLE_ENDIAN);
            final ByteBuffer mono = ByteBuffer.allocate (count * 2).order (ByteOrder.LITTLE_ENDIAN);
            for (int frame = start; frame < stop; frame++)
            {
                long sum = 0;
                for (int channel = 0; channel < channels; channel++)
                    sum += input.getShort ((frame * channels + channel) * 2);
                final int index = zone.isReversed () ? stop - 1 - frame : frame - start;
                mono.putShort (index * 2, (short) (sum / channels));
            }
            final String note = noteName (zone.getKeyRoot ());
            final Path target = uniquePath (folder, name, "_" + note + ".wav", 64);
            this.notifier.log ("IDS_NOTIFY_STORING", target.toString ());
            // A zone is an independent sample, not a slice chain. Never invent ORSL markers.
            Files.write (target, Lofi12XtWav.build (rate, mono.array (), tags, List.of ()), StandardOpenOption.CREATE_NEW);
        }
        this.progress.notifyDone ();
    }

    private static Path uniquePath (final Path parent, final String name, final String suffix, final int limit)
    {
        for (int i = 1; ; i++)
        {
            final String serial = i == 1 ? "" : "-" + i;
            final int length = Math.min (name.length (), limit - suffix.length () - serial.length ());
            final String stem = name.substring (0, length).replaceAll ("-+$", "");
            final Path path = parent.resolve (stem + serial + suffix);
            if (!Files.exists (path))
                return path;
        }
    }

    static String kebab (final String name)
    {
        final String value = Normalizer.normalize (name == null ? "" : name, Normalizer.Form.NFKD).replaceAll ("\\p{M}+", "").toLowerCase (Locale.ROOT).replaceAll ("[^a-z0-9]+", "-").replaceAll ("^-+|-+$", "");
        return value.isEmpty () ? "sample" : value;
    }

    private static String noteName (final int key)
    {
        return key < 0 || key > 127 ? "unknown" : NOTES[key % 12] + (key / 12 - 1);
    }

    /** Device bit order differs from pad order. Unknown categories remain untagged. */
    static long categoryTags (final String category)
    {
        if (category == null)
            return 0;
        return switch (category)
        {
            case TagDetector.CATEGORY_KICK, "BD" -> 1L;
            case TagDetector.CATEGORY_SNARE, "SD" -> 1L << 2;
            case TagDetector.CATEGORY_HI_HAT, "HH" -> 1L << 4;
            case TagDetector.CATEGORY_ACOUSTIC_DRUM, TagDetector.CATEGORY_DRUM, "DR" -> 1L << 6;
            case TagDetector.CATEGORY_BELL, TagDetector.CATEGORY_CHROMATIC_PERCUSSION, TagDetector.CATEGORY_CLAP, TagDetector.CATEGORY_PERCUSSION, "PC" -> 1L << 8;
            case TagDetector.CATEGORY_BASS, "BS" -> 1L << 10;
            case TagDetector.CATEGORY_GUITAR, "GT" -> 1L << 12;
            case TagDetector.CATEGORY_KEYBOARD, TagDetector.CATEGORY_ORGAN, TagDetector.CATEGORY_PIANO, "KB" -> 1L << 14;
            case TagDetector.CATEGORY_CHIP, TagDetector.CATEGORY_DRONE, TagDetector.CATEGORY_LEAD, TagDetector.CATEGORY_MONOSYNTH, TagDetector.CATEGORY_PAD, TagDetector.CATEGORY_PLUCK, TagDetector.CATEGORY_SYNTH, "SY" -> 1L << 1;
            case TagDetector.CATEGORY_ENSEMBLE, TagDetector.CATEGORY_ORCHESTRAL, TagDetector.CATEGORY_STRINGS, "ST" -> 1L << 3;
            case TagDetector.CATEGORY_BRASS, TagDetector.CATEGORY_PIPE, TagDetector.CATEGORY_WINDS, "WN" -> 1L << 5;
            case TagDetector.CATEGORY_VOCAL, "VO" -> 1L << 7;
            case TagDetector.CATEGORY_DESTRUCTION, TagDetector.CATEGORY_FX, TagDetector.CATEGORY_HITS -> 1L << 9;
            case TagDetector.CATEGORY_LOOPS, "Loop" -> 1L << 11;
            case "Slice", "Slices" -> 1L << 13;
            default -> 0;
        };
    }
}
