package org.jenkinsci.plugins;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.commons.io.FileUtils;
import org.jenkinsci.Symbol;
import org.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import jenkins.tasks.SimpleBuildStep;

public class AssessmentBuilder extends Builder implements SimpleBuildStep {

  // private final static String tempDir = System.getProperty("java.io.tmpdir");
  // private final static String tempDir = "/var/jenkins_home/tests";
  private final static String jenkinsHomeDir = "/var/jenkins_home";
  private final static String testDir = jenkinsHomeDir + "/tests";
  private final static String workspaceDir = jenkinsHomeDir + "/workspace";

  private String jobName = ""; // for workspace
  private String testFileName = ""; // tomcat test zip file path
  // ex. oop-hw1
  private String proDetailUrl = "";
  // http://140.134.26.71:10088/ProgEdu/webapi/project/checksum?proName=OOP-HW1
  private String testFileUrl = "";
  // http://140.134.26.71:10088/ProgEdu/webapi/jenkins/getTestFile?filePath=/usr/local/tomcat/temp/test/oop-hw1.zip
  private String testFileChecksum = "";
  // 12345678
  private String testFilePath = "";

  @DataBoundConstructor
  public AssessmentBuilder(String jobName, String testFileName, String proDetailUrl, String testFileUrl,
      String testFileChecksum) {
    this.jobName = jobName;
    this.testFileName = testFileName;
    this.proDetailUrl = proDetailUrl;
    this.testFileUrl = testFileUrl;
    this.testFileChecksum = testFileChecksum;
  }

  public String getJobName() {
    return jobName;
  }

  public String getTestFileName() {
    return testFileName;
  }

  public String getProDetailUrl() {
    return proDetailUrl;
  }

  public String getTestFileUrl() {
    return testFileUrl;
  }

  public String getTestFileChecksum() {
    return testFileChecksum;
  }

  public String setUpCpCommand() {
    String cpCommand = "";
    // cpCommand = "cp -r " + tempDir + "/progedu/" + testFileName + "
    // /var/jenkins_home/workspace/" + jobName + "/src";
    cpCommand = "cp -r " + testFilePath + "/src/test " + workspaceDir + "/" + jobName + "/src";
    return cpCommand;
  }

  public void cpFile() {
    File dataFile = new File(testFilePath);
    File targetFile = new File(workspaceDir + "/" + jobName);
    if (targetFile.isDirectory()) {// Check if it is the same file
      try {
        FileUtils.copyDirectory(dataFile, targetFile);
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
  }

  public void execLinuxCommand(String command) {
    Process process;

    try {
      process = Runtime.getRuntime().exec(command);
      BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream()));
      String line;
      while (true) {
        line = br.readLine();
        if (line == null) {
          break;
        }
        System.out.println(line);
      }
    } catch (IOException e) {
      e.printStackTrace();
    }

  }

  public String getProDetailJson(String proDetailUrl) {
    String json = null;
    HttpURLConnection conn = null;
    try {
      if (Thread.interrupted()) {
        throw new InterruptedException();
      }

      URL url = new URL(proDetailUrl);
      conn = (HttpURLConnection) url.openConnection();
      conn.setReadTimeout(10000);
      conn.setConnectTimeout(15000);
      conn.setRequestMethod("GET");
      conn.connect();
      if (Thread.interrupted()) {
        throw new InterruptedException();
      }

      BufferedReader reader = new BufferedReader(
          new InputStreamReader(conn.getInputStream(), "UTF-8"));
      String jsonString1 = reader.readLine();
      reader.close();

      json = jsonString1;

    } catch (Exception e) {
      System.out.print("Error : ");
      e.printStackTrace();
    } finally {
      if (conn != null) {
        conn.disconnect();
      }
    }
    return json;
  }

  private String getCurrentChecksum(String strJson) {
    JSONObject json = new JSONObject(strJson);
    String currentChecksum = json.getString("testZipChecksum");
    return currentChecksum;
  }

  private String getTestZipUrl(String strJson) {
    JSONObject json = new JSONObject(strJson);
    String testZipUrl = json.getString("testZipUrl");
    return testZipUrl;
  }

  private boolean checksumEquals(String currentChecksum) {
    boolean same = true;

    if (testFileChecksum.isEmpty()) {
      same = false;
    } else {
      if (currentChecksum.equals(testFileChecksum)) {
        same = true;
      } else {
        same = false;
      }
    }
    return same;
  }

  private void downloadTestZipFromTomcat(String testZipUrl, TaskListener listener) {
    URL website;

    // String strUrl =
    // "http://140.134.26.71:10088/ProgEdu/webapi/jenkins/getTestFile?filePath="
    // + "/usr/local/tomcat/temp/test/" + testFileName + ".zip";
    // http://140.134.26.71:10088/ProgEdu/webapi/jenkins/getTestFile?filePath=/usr/local/tomcat/temp/test/oop-hw1.zip

    // destZipPath = test zip file path
    String destZipPath = testFilePath + ".zip";
    // /var/jenkins_home/tests/oop-hw1.zip
    FileOutputStream fos = null;
    try {
      website = new URL(testZipUrl);
      ReadableByteChannel rbc = Channels.newChannel(website.openStream());
      fos = new FileOutputStream(destZipPath);
      fos.getChannel().transferFrom(rbc, 0, Long.MAX_VALUE);
    } catch (IOException e) {
      listener.getLogger().println("exception : " + e.getMessage());
      e.printStackTrace();
    } finally {
      try {
        fos.close();
      } catch (IOException e) {
        listener.getLogger().println("exception : " + e.getMessage());
      }
    }
    unzip(destZipPath, testFilePath, listener);
  }

  /**
   * Size of the buffer to read/write data
   */
  private static final int BUFFER_SIZE = 4096;

  /**
   * Extracts a zip file specified by the zipFilePath to a directory specified
   * by destDirectory (will be created if does not exists)
   * 
   * @param zipFilePath
   * @param destDirectory
   */
  private void unzip(String zipFilePath, String destDirectory, TaskListener listener) {
    File destDir = new File(destDirectory);
    if (!destDir.exists()) {
      destDir.mkdir();
    }
    ZipInputStream zipIn;
    try {
      zipIn = new ZipInputStream(new FileInputStream(zipFilePath));
      ZipEntry entry = zipIn.getNextEntry();
      // iterates over entries in the zip file
      while (entry != null) {
        String filePath = destDirectory + File.separator + entry.getName();
        File newFile = new File(filePath);

        System.out.println("filePath : " + filePath);
        listener.getLogger().println("filePath : " + filePath);

        // create all non exists folders
        // else you will hit FileNotFoundException for compressed folder
        new File(newFile.getParent()).mkdirs();

        if (!entry.isDirectory()) {
          // if the entry is a file, extracts it
          extractFile(zipIn, filePath);
        } else {
          // if the entry is a directory, make the directory
          File dir = new File(filePath);
          dir.mkdir();
        }
        zipIn.closeEntry();
        entry = zipIn.getNextEntry();
      }
      zipIn.close();
    } catch (IOException e) {
      listener.getLogger().println("exception : " + e.getMessage());
      e.printStackTrace();
    }
  }

  /**
   * Extracts a zip entry (file entry)
   * 
   * @param zipIn
   * @param filePath
   * @throws IOException
   */
  private static void extractFile(ZipInputStream zipIn, String filePath) throws IOException {
    BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(filePath));
    byte[] bytesIn = new byte[BUFFER_SIZE];
    int read = 0;
    while ((read = zipIn.read(bytesIn)) != -1) {
      bos.write(bytesIn, 0, read);
    }
    bos.close();
  }

  @Override
  public void perform(Run<?, ?> run, FilePath workspace, Launcher launcher, TaskListener listener)
      throws InterruptedException, IOException {

    // /var/jenkins_home/tests/oop-hw1
    testFilePath = testDir + "/" + testFileName;

    File testDirFile = new File(testDir);
    if (!testDirFile.exists()) {
      testDirFile.mkdirs();
    }

    String strJson = null;
    String currentChecksum = null;
    if (!proDetailUrl.equals("")) {
      listener.getLogger().println("proDetailUrl : " + proDetailUrl);
      strJson = getProDetailJson(proDetailUrl);
      currentChecksum = getCurrentChecksum(strJson);
      listener.getLogger().println("strJson : " + strJson);
    }
    boolean checksumEquals = checksumEquals(currentChecksum);
    listener.getLogger().println("checksumEquals : " + checksumEquals);

    if (!checksumEquals) {
      // checksum is diff, download zip file
      String testZipUrl = getTestZipUrl(strJson);

      testFileChecksum = currentChecksum;
      testFileUrl = testZipUrl;

      listener.getLogger().println("currentChecksum : " + currentChecksum);
      listener.getLogger().println("testZipUrl : " + testZipUrl);

      // download test file if test file is not exist
      listener.getLogger().println("testFilePath : " + testFilePath);
      File testFile = new File(testFilePath);
      if (testFile.exists()) {
        testFile.delete();
      }
      downloadTestZipFromTomcat(testZipUrl, listener);
    } else {
      // checksum is same, do nothing
    }

    listener.getLogger().println("testFilePath : " + testFilePath);

    // cpFile();
    String cpCommand = setUpCpCommand();
    // execLinuxCommand(cpCommand);
    cpFile();
    listener.getLogger().println("cpCommand : " + cpCommand);
    listener.getLogger().println("jobName : " + jobName);
    listener.getLogger().println("testFileName : " + testFileName);

  }

  @Symbol("greet")
  @Extension
  public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

    @Override
    public boolean isApplicable(Class<? extends AbstractProject> jobType) {
      return true;
    }

    @Override
    public String getDisplayName() {
      return "ProgEdu Unit Test Assessment";
    }

  }

}
