/*
 * Copyright (c) 2016, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.device.mgt.iot.util;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.base.ServerConfiguration;
import org.wso2.carbon.device.mgt.common.DeviceManagementException;
import org.wso2.carbon.utils.CarbonUtils;
import org.wso2.carbon.utils.NetworkUtils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Provides utility methods required by the device type plugins.
 */
public class Utils {

    public static final String HOST_NAME = "HostName";
    private static final Log log = LogFactory.getLog(Utils.class);

    public static String getServerUrl() {
        String hostName = ServerConfiguration.getInstance().getFirstProperty(HOST_NAME);
        try {
            if (hostName == null) {
                hostName = NetworkUtils.getLocalHostname();
            }
        } catch (SocketException e) {
            hostName = "localhost";
            log.warn("Failed retrieving the hostname, therefore set to localhost", e);
        }
        return hostName;
    }

    public static ZipArchive getSketchArchive(String archivesPath, String templateSketchPath, Map contextParams,
                                              String zipFileName)
            throws DeviceManagementException, IOException {
        String sketchPath = CarbonUtils.getCarbonHome() + File.separator + templateSketchPath;
        FileUtils.deleteDirectory(new File(archivesPath));//clear directory
        FileUtils.deleteDirectory(new File(archivesPath + ".zip"));//clear zip
        if (!new File(archivesPath).mkdirs()) { //new dir
            String message = "Could not create directory at path: " + archivesPath;
            log.error(message);
            throw new DeviceManagementException(message);
        }
        zipFileName = zipFileName + ".zip";
        try {
            Map<String, List<String>> properties = getProperties(sketchPath + File.separator + "sketch" + ".properties");
            List<String> templateFiles = properties.get("templates");

            for (String templateFile : templateFiles) {
                parseTemplate(templateSketchPath + File.separator + templateFile, archivesPath + File.separator + templateFile,
                              contextParams);
            }

            templateFiles.add("sketch.properties");         // ommit copying the props file
            copyFolder(new File(sketchPath), new File(archivesPath), templateFiles);
            createZipArchive(archivesPath);
            FileUtils.deleteDirectory(new File(archivesPath));
            File zip = new File(archivesPath + ".zip");
            return new ZipArchive(zipFileName, zip);
        } catch (IOException ex) {
            throw new DeviceManagementException(
                    "Error occurred when trying to read property " + "file sketch.properties", ex);
        }
    }

    private static Map<String, List<String>> getProperties(String propertyFilePath) throws IOException {
        Properties prop = new Properties();
        InputStream input = null;

        try {

            input = new FileInputStream(propertyFilePath);

            // load a properties file
            prop.load(input);
            Map<String, List<String>> properties = new HashMap<String, List<String>>();

            String templates = prop.getProperty("templates");
            List<String> list = new ArrayList<String>(Arrays.asList(templates.split(",")));
            properties.put("templates", list);

            final String filename = prop.getProperty("zipfilename");
            list = new ArrayList<String>() {{
                add(filename);
            }};
            properties.put("zipfilename", list);
            return properties;

        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException e) {
                    log.error("Failed closing connection", e);
                }
            }
        }
    }

    private static void parseTemplate(String srcFile, String dstFile, Map contextParams) throws IOException {
        //read from file
        FileInputStream inputStream = null;
        FileOutputStream outputStream = null;
        try {
            inputStream = new FileInputStream(srcFile);
            outputStream = new FileOutputStream(dstFile);
            String content = IOUtils.toString(inputStream, StandardCharsets.UTF_8.toString());
            Iterator iterator = contextParams.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry mapEntry = (Map.Entry) iterator.next();
                content = content.replaceAll("\\$\\{" + mapEntry.getKey() + "\\}", mapEntry.getValue().toString());
            }
            IOUtils.write(content, outputStream, StandardCharsets.UTF_8.toString());
        } finally {
            if (inputStream != null) {
                inputStream.close();
            }
            if (outputStream != null) {
                outputStream.close();
            }
        }
    }

    private static void copyFolder(File src, File dest, List<String> excludeFileNames) throws IOException {

        if (src.isDirectory()) {
            //if directory not exists, create it
            if (!dest.exists() && !dest.mkdirs()) {
                String message = "Could not create directory at path: " + dest;
                log.error(message);
                throw new IOException(message);
            }
            //list all the directory contents
            String files[] = src.list();

            if (files == null) {
                log.warn("There are no files insides the directory " + src.getAbsolutePath());
                return;
            }

            for (String file : files) {
                //construct the src and dest file structure
                File srcFile = new File(src, file);
                File destFile = new File(dest, file);
                //recursive copy
                copyFolder(srcFile, destFile, excludeFileNames);
            }

        } else {
            for (String fileName : excludeFileNames) {
                if (src.getName().equals(fileName)) {
                    return;
                }
            }
            //if file, then copy it
            //Use bytes stream to support all file types
            InputStream in = null;
            OutputStream out = null;

            try {
                in = new FileInputStream(src);
                out = new FileOutputStream(dest);

                byte[] buffer = new byte[1024];

                int length;
                //copy the file content in bytes
                while ((length = in.read(buffer)) > 0) {
                    out.write(buffer, 0, length);
                }
            } finally {
                if (in != null) {
                    in.close();
                }
                if (out != null) {
                    out.close();
                }
            }
        }
    }

    private static boolean createZipArchive(String srcFolder) throws IOException {
        BufferedInputStream origin = null;
        ZipOutputStream out = null;

        try {
            final int BUFFER = 2048;
            FileOutputStream dest = new FileOutputStream(new File(srcFolder + ".zip"));
            out = new ZipOutputStream(new BufferedOutputStream(dest));
            byte data[] = new byte[BUFFER];
            File subDir = new File(srcFolder);
            String subdirList[] = subDir.list();
            if (subdirList == null) {
                log.warn("The sub directory " + subDir.getAbsolutePath() + " is empty");
                return false;
            }
            for (String sd : subdirList) {
                // get a list of files from current directory
                File f = new File(srcFolder + "/" + sd);
                if (f.isDirectory()) {
                    String files[] = f.list();

                    if (files == null) {
                        log.warn("The current directory " + f.getAbsolutePath() + " is empty. Has no files");
                        return false;
                    }

                    for (int i = 0; i < files.length; i++) {
                        FileInputStream fi = new FileInputStream(srcFolder + "/" + sd + "/" + files[i]);
                        origin = new BufferedInputStream(fi, BUFFER);
                        ZipEntry entry = new ZipEntry(sd + "/" + files[i]);
                        out.putNextEntry(entry);
                        int count;
                        while ((count = origin.read(data, 0, BUFFER)) != -1) {
                            out.write(data, 0, count);
                            out.flush();
                        }

                    }
                } else //it is just a file
                {
                    FileInputStream fi = new FileInputStream(f);
                    origin = new BufferedInputStream(fi, BUFFER);
                    ZipEntry entry = new ZipEntry(sd);
                    out.putNextEntry(entry);
                    int count;
                    while ((count = origin.read(data, 0, BUFFER)) != -1) {
                        out.write(data, 0, count);
                        out.flush();
                    }
                }
            }
            out.flush();
        } finally {
            if (origin != null) {
                origin.close();
            }
            if (out != null) {
                out.close();
            }
        }
        return true;
    }

}
