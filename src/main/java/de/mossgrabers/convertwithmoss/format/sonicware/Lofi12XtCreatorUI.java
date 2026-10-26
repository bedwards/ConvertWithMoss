// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt
package de.mossgrabers.convertwithmoss.format.sonicware;

import java.util.List;
import java.util.Map;

import de.mossgrabers.convertwithmoss.core.INotifier;
import de.mossgrabers.convertwithmoss.core.settings.ICoreTaskSettings;
import de.mossgrabers.tools.ui.BasicConfig;
import de.mossgrabers.tools.ui.panel.BoxPanel;
import javafx.geometry.Orientation;
import javafx.scene.control.ComboBox;
import javafx.scene.layout.Pane;

/** Destination options shared by the UI and command line. */
public class Lofi12XtCreatorUI implements ICoreTaskSettings
{
    private static final String RATE = "Lofi12XtSampleRate";
    private static final String NOTES = "Lofi12XtNotes";
    private ComboBox<String> rateBox;
    private ComboBox<String> notesBox;
    private int sampleRate = 24000;
    private boolean allZones = false;

    @Override
    public Pane getEditPane ()
    {
        final BoxPanel panel = new BoxPanel (Orientation.VERTICAL);
        this.rateBox = panel.createComboBox ("@IDS_LOFI12XT_RATE", List.of ("24 kHz", "12 kHz"));
        this.notesBox = panel.createComboBox ("@IDS_LOFI12XT_NOTES", List.of ("All C zones", "Every zone"));
        this.rateBox.getSelectionModel ().select (this.sampleRate == 24000 ? 0 : 1);
        this.notesBox.getSelectionModel ().select (this.allZones ? 1 : 0);
        return panel.getPane ();
    }

    @Override
    public void saveSettings (final BasicConfig config)
    {
        config.setProperty (RATE, this.rateBox.getSelectionModel ().getSelectedIndex () == 1 ? "12000" : "24000");
        config.setProperty (NOTES, this.notesBox.getSelectionModel ().getSelectedIndex () == 1 ? "all" : "c");
    }

    @Override
    public void loadSettings (final BasicConfig config)
    {
        this.rateBox.getSelectionModel ().select ("12000".equals (config.getProperty (RATE, "24000")) ? 1 : 0);
        this.notesBox.getSelectionModel ().select ("all".equals (config.getProperty (NOTES, "c")) ? 1 : 0);
    }

    @Override
    public boolean checkSettingsUI (final INotifier notifier)
    {
        this.sampleRate = this.rateBox.getSelectionModel ().getSelectedIndex () == 1 ? 12000 : 24000;
        this.allZones = this.notesBox.getSelectionModel ().getSelectedIndex () == 1;
        return true;
    }

    @Override
    public boolean checkSettingsCLI (final INotifier notifier, final Map<String, String> parameters)
    {
        final String rate = parameters.remove (RATE);
        final String notes = parameters.remove (NOTES);
        if (rate != null && !"12000".equals (rate) && !"24000".equals (rate) || notes != null && !"c".equals (notes) && !"all".equals (notes))
        {
            notifier.logError ("IDS_LOFI12XT_OPTIONS");
            return false;
        }
        this.sampleRate = "12000".equals (rate) ? 12000 : 24000;
        this.allZones = "all".equals (notes);
        return true;
    }

    @Override
    public String [] getCLIParameterNames ()
    {
        return new String [] { RATE, NOTES };
    }

    public int getSampleRate ()
    {
        return this.sampleRate;
    }

    public boolean isAllZones ()
    {
        return this.allZones;
    }
}
