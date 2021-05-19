package com.nccgroup.loggerplusplus.exports;

import com.coreyd97.BurpExtenderUtilities.Alignment;
import com.coreyd97.BurpExtenderUtilities.ComponentGroup;
import com.coreyd97.BurpExtenderUtilities.PanelBuilder;
import com.coreyd97.BurpExtenderUtilities.Preferences;
import com.nccgroup.loggerplusplus.LoggerPlusPlus;
import com.nccgroup.loggerplusplus.filter.logfilter.LogFilter;
import com.nccgroup.loggerplusplus.filter.parser.ParseException;
import com.nccgroup.loggerplusplus.filterlibrary.FilterLibraryController;
import com.nccgroup.loggerplusplus.logentry.LogEntryField;
import com.nccgroup.loggerplusplus.util.Globals;
import com.nccgroup.loggerplusplus.util.MoreHelp;
import org.apache.commons.lang3.StringUtils;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;
import java.util.Objects;

import static com.nccgroup.loggerplusplus.util.Globals.*;

public class CobaltExporterConfigDialog extends JDialog {

    CobaltExporterConfigDialog(Frame owner, CobaltExporter cobaltExporter){
        super(owner, "Cobalt Exporter Configuration", true);

        this.setLayout(new BorderLayout());
        Preferences preferences = cobaltExporter.getPreferences();

        JTextField addressField = PanelBuilder.createPreferenceTextField(preferences, PREF_COBALT_ADDRESS);

        //TODO Update PanelBuilder to allow labels with custom components

        JSpinner cobaltDelaySpinner = PanelBuilder.createPreferenceSpinner(preferences, PREF_COBALT_DELAY);
        ((SpinnerNumberModel) cobaltDelaySpinner.getModel()).setMaximum(99999);
        ((SpinnerNumberModel) cobaltDelaySpinner.getModel()).setMinimum(10);
        ((SpinnerNumberModel) cobaltDelaySpinner.getModel()).setStepSize(10);

        JButton configureFieldsButton = new JButton(new AbstractAction("Configure") {
            @Override
            public void actionPerformed(ActionEvent actionEvent) {
                List<LogEntryField> selectedFields = MoreHelp.showFieldChooserDialog(addressField,
                        preferences, "Cobalt Exporter", cobaltExporter.getFields());

                if(selectedFields == null){
                    //Cancelled.
                } else if (!selectedFields.isEmpty()) {
                    cobaltExporter.setFields(selectedFields);
                } else {
                    JOptionPane.showMessageDialog(addressField,
                            "No fields were selected. No changes have been made.",
                            "Cobalt Exporter", JOptionPane.INFORMATION_MESSAGE);
                }
            }
        });


        String projectPreviousFilterString = preferences.getSetting(Globals.PREF_ELASTIC_FILTER_PROJECT_PREVIOUS);
        String filterString = preferences.getSetting(Globals.PREF_ELASTIC_FILTER);
        if (projectPreviousFilterString != null && !Objects.equals(projectPreviousFilterString, filterString)) {
            int res = JOptionPane.showConfirmDialog(LoggerPlusPlus.instance.getLoggerFrame(),
                    "Looks like the log filter has been changed since you last used this Burp project.\n" +
                            "Do you want to restore the previous filter used by the project?\n" +
                            "\n" +
                            "Previously used filter: " + projectPreviousFilterString + "\n" +
                            "Current filter: " + filterString, "CobaltSearch Exporter Log Filter",
                    JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (res == JOptionPane.YES_OPTION) {
                preferences.setSetting(PREF_ELASTIC_FILTER, projectPreviousFilterString);
            }
        }

        JTextField filterField = PanelBuilder.createPreferenceTextField(preferences, PREF_ELASTIC_FILTER);
        filterField.setMinimumSize(new Dimension(600, 0));

        JCheckBox autostartGlobal = PanelBuilder.createPreferenceCheckBox(preferences, PREF_COBALT_AUTOSTART_GLOBAL);
        JCheckBox autostartProject = PanelBuilder.createPreferenceCheckBox(preferences, PREF_COBALT_AUTOSTART_PROJECT);

        //If global autostart is on, it overrides the per-project setting.
        autostartProject.setEnabled(!(boolean) preferences.getSetting(PREF_COBALT_AUTOSTART_GLOBAL));
        preferences.addSettingListener((source, settingName, newValue) -> {
            if (Objects.equals(settingName, PREF_COBALT_AUTOSTART_GLOBAL)) {
                autostartProject.setEnabled(!(boolean) newValue);
                if ((boolean) newValue) {
                    preferences.setSetting(PREF_COBALT_AUTOSTART_PROJECT, true);
                }
            }
        });

//        new JComponent[]{new JLabel("Address: "), addressField},
//                new JComponent[]{new JLabel("Port: "), cobaltPortSpinner},
//                new JComponent[]{new JLabel("Protocol: "), protocolSelector},

        ComponentGroup connectionGroup = new ComponentGroup(ComponentGroup.Orientation.VERTICAL, "Connection");
        connectionGroup.addComponentWithLabel("Address: ", addressField);

        ComponentGroup miscGroup = new ComponentGroup(ComponentGroup.Orientation.VERTICAL, "Misc");
        miscGroup.add(PanelBuilder.build(new Component[][]{
                new JComponent[]{new JLabel("Upload Frequency (Seconds): "), cobaltDelaySpinner},
                new JComponent[]{new JLabel("Exported Fields: "), configureFieldsButton},
                new JComponent[]{new JLabel("Log Filter: "), filterField},
                new JComponent[]{new JLabel("Autostart Exporter (All Projects): "), autostartGlobal},
                new JComponent[]{new JLabel("Autostart Exporter (This Project): "), autostartProject},
        }, new int[][]{
                new int[]{0, 1},
                new int[]{0, 1},
                new int[]{0, 1},
                new int[]{0, 1},
                new int[]{0, 1}
        }, Alignment.FILL, 1, 1));


        this.add(PanelBuilder.build(new JComponent[][]{
                new JComponent[]{connectionGroup},
                new JComponent[]{miscGroup}
        }, new int[][]{
                new int[]{1},
                new int[]{1},
                new int[]{1},
        }, Alignment.CENTER, 1.0, 1.0, 5, 5), BorderLayout.CENTER);

        this.setMinimumSize(new Dimension(600, 200));

        this.pack();
        this.setResizable(true);
        this.setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);

        this.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                String logFilter = preferences.getSetting(PREF_ELASTIC_FILTER);

                if (!StringUtils.isBlank(logFilter)) {
                    FilterLibraryController libraryController = cobaltExporter.getExportController()
                            .getLoggerPlusPlus().getLibraryController();
                    try {
                        new LogFilter(libraryController, logFilter);
                    } catch (ParseException ex) {
                        JOptionPane.showMessageDialog(CobaltExporterConfigDialog.this,
                                "Cannot save Cobalt Exporter configuration. The chosen log filter is invalid: \n" +
                                        ex.getMessage(), "Invalid Cobalt Exporter Configuration", JOptionPane.ERROR_MESSAGE);
                        return;
                    }
                }
                CobaltExporterConfigDialog.this.dispose();
                super.windowClosing(e);
            }
        });
    }
}
