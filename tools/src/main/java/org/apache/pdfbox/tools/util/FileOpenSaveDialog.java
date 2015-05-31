/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.pdfbox.tools.util;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import org.apache.pdfbox.tools.ExtensionFileFilter;
import org.apache.pdfbox.tools.PDFDebugger;

/**
 * @author Khyrul Bashar
 *
 * A Customized class that helps to open and save file. It uses JFileChooser to operate.
 */
public class FileOpenSaveDialog
{
    PDFDebugger mainUI;

    private static final JFileChooser fileChooser = new JFileChooser() 
    {
        @Override
        public void approveSelection()
        {
            File selectedFile = getSelectedFile();
            if (selectedFile.exists() && getDialogType() == JFileChooser.SAVE_DIALOG)
            {
                int result = JOptionPane.showConfirmDialog(this,
                        "Do you want to overwrite?",
                        "File already exists",
                        JOptionPane.YES_NO_OPTION);
                if (result == JOptionPane.YES_OPTION)
                {
                    super.approveSelection();
                }
                else
                {
                    cancelSelection();
                }
            }
            super.approveSelection();
        }
    };

    /**
     * Constructor.
     * @param parentUI the main UI (JFrame) on top of which File open/save dialog should open.
     * @param fileFilter file Filter, null is allowed when no filter is applicable.
     */
    public FileOpenSaveDialog(PDFDebugger parentUI, ExtensionFileFilter fileFilter)
    {
        mainUI = parentUI;
        fileChooser.setFileFilter(fileFilter);
    }

    /**
     * saves a file while user is prompted to chose the destination.
     * @param bytes byte array of the saving file.
     * @return true if file is saved successfully or false if failed.
     * @throws IOException if there is error in creation of file.
     */
    public boolean saveFile(byte[] bytes) throws IOException
    {
        int result = fileChooser.showSaveDialog(mainUI);
        if (result == JFileChooser.APPROVE_OPTION)
        {
            File selectedFile = fileChooser.getSelectedFile();
            FileOutputStream outputStream = new FileOutputStream(selectedFile);
            outputStream.write(bytes);
            outputStream.close();
            return true;
        }
        return false;
    }

    /**
     * open a file prompting user to select the file.
     * @return the file opened.
     * @throws IOException if there is error in opening the file.
     */
    public File openFile() throws IOException
    {
        int result = fileChooser.showOpenDialog(mainUI);
        if (result == JFileChooser.APPROVE_OPTION)
        {
            return fileChooser.getSelectedFile();
        }
        return null;
    }
}
