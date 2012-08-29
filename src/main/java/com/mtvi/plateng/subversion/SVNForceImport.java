package com.mtvi.plateng.subversion;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.tmatesoft.svn.core.SVNCommitInfo;
import org.tmatesoft.svn.core.SVNDepth;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNNodeKind;
import org.tmatesoft.svn.core.SVNProperties;
import org.tmatesoft.svn.core.SVNURL;
import org.tmatesoft.svn.core.auth.ISVNAuthenticationManager;
import org.tmatesoft.svn.core.internal.io.dav.DAVRepositoryFactory;
import org.tmatesoft.svn.core.internal.io.fs.FSRepositoryFactory;
import org.tmatesoft.svn.core.internal.io.svn.SVNRepositoryFactoryImpl;
import org.tmatesoft.svn.core.io.SVNRepository;
import org.tmatesoft.svn.core.io.SVNRepositoryFactory;
import org.tmatesoft.svn.core.wc.SVNClientManager;
import org.tmatesoft.svn.core.wc.SVNCommitClient;
import org.tmatesoft.svn.core.wc.SVNCommitPacket;
import org.tmatesoft.svn.core.wc.SVNInfo;
import org.tmatesoft.svn.core.wc.SVNRevision;
import org.tmatesoft.svn.core.wc.SVNUpdateClient;
import org.tmatesoft.svn.core.wc.SVNWCClient;
import org.tmatesoft.svn.core.wc.SVNWCUtil;

import com.google.common.io.Files;

/**
 * SVNForceImport can be used to import a maven project into an svn repository.
 * It has the ability to import numerous different files/folders based on
 * matching a regular expression pattern. Each matched item can be renamed and
 * placed in differing folders.
 * 
 * SVNForceImport can also read a projects pom file and extract Major Minor and
 * Patch version numbers.
 * 
 * @author bsmith
 * @version 0.1
 */
public class SVNForceImport {
    private static final Logger LOGGER = Logger.getLogger(SVNForceImport.class
        .getName());

    /**
     * Main method, used by hudson until a plugin wrapper can be written.
     * 
     * @param args
     *            Arguments consist of switches and values as follows:
     *            <p>
     *            <ul>
     * 
     *            <li>-r <code>repository_url</code><br>
     *            REQUIRED: The url of the repository to be used, should include
     *            the path to the project.<br>
     *            <br>
     * 
     *            <li>-t <code>target_path</code><br>
     *            REQUIRED: The path to the local target directory.<br>
     *            <br>
     * 
     *            <li>-pom <code>pom.xml_path</code><br>
     *            REQUIRED: The path to the project's pom file.<br>
     *            <br>
     * 
     *            <li>-i <code>file_pattern</code> <code>remote_path</code>
     *            <code>remote_name</code><br>
     *            REQUIRED, MULTIPLE: The item(s) (file/folder) to be imported.<br>
     *            All arguments may use _MAJOR_,_MINOR_, and _PATCH_ to replace
     *            with pom values.<br>
     *            <ul>
     *            <li><code>file_pattern</code>: Only items fitting this pattern
     *            will be imported.<br>
     *            <li><code>remote_path</code>: Path from remote project to
     *            desired location, ends in "/". _ROOT_ places item in project
     *            root.<br>
     *            <li><code>remote_name</code>: Final name for imported item,
     *            multiple items matching the same pattern are prepended with
     *            numbers.<br>
     *            </ul>
     *            <br>
     *            <br>
     * 
     * 
     *            <li>-u <code>svn_username</code><br>
     *            OPTIONAL: svn username<br>
     *            <br>
     * 
     *            <li>-p <code>svn_password</code><br>
     *            OPTIONAL: svn password<br>
     *            </ul>
     */
    public static void main(final String[] args) {

        ArrayList<ImportItem> importItems = new ArrayList<ImportItem>();
        String svnURL = "";
        String pomPath = "";
        String target = "";
        String user = "";
        String password = "";

        // read through given options
        // I thought about creating an enumeration for these, but I'd rather
        // just write the wrapper.
        try {
            for (int i = 0; i < args.length;) {

                if (args[i].equalsIgnoreCase("-r")) {
                    // set repositoryURL
                    svnURL = args[i + 1];
                    i += 2;

                } else if (args[i].equalsIgnoreCase("-i")) {
                    // add item
                    importItems.add(new ImportItem(args[i + 1], args[i + 2],
                        args[i + 3]));
                    i += 4;

                } else if (args[i].equalsIgnoreCase("-u")) {
                    // set username
                    user = args[i + 1];
                    i += 2;

                } else if (args[i].equalsIgnoreCase("-p")) {
                    // set password
                    password = args[i + 1];
                    i += 2;

                } else if (args[i].equalsIgnoreCase("-pom")) {
                    // set pomPath
                    pomPath = args[i + 1];
                    i += 2;
                } else if (args[i].equalsIgnoreCase("-t")) {
                    // set pomPath
                    target = args[i + 1];
                    i += 2;
                }
            }
            if (svnURL.length() == 0) {
                System.err
                    .println("SVNForceImport Error: Missing repository URL\n");
            }
        } catch (Exception e) {
            System.err
                .println("SVNForceImport Error: Error while parsing options\n");
        }
        forceImport(svnURL, user, password, target, importItems, pomPath, null,
            null, null, null, null);

    }

    /**
     * The core SVNForceImport method, used to import files into a repository.
     * 
     * @param svnURL
     *            The url of the repository including path to project root.
     * @param user
     *            The username to use for repository access.
     * @param password
     *            The password to use for repository access.
     * @param target
     *            The path to the local target directory, where items are found.
     * @param items
     *            The ImportItems to be imported.
     * @param pomPath
     *            The path to the project's pom.xml file.
     * @param majorPath
     *            The xml path to the major version in the pom file.
     * @param minorPath
     *            The xml path to the minor version in the pom file.
     * @param patchPath
     *            The xml path to the patch version in the pom file.
     */
    @SuppressWarnings("deprecation")
    public static void forceImport(final String svnURL, final String user,
        final String password, String target,
        final ArrayList<ImportItem> items, String pomPath,
        final String majorPath, final String minorPath,
        final String patchPath, String workspace, final PrintStream stream) {

        if (null != workspace) {

            workspace = workspace.substring(0, workspace.length() - 1);
            target = target.replaceAll("_WORKSPACE_", workspace);
            target = target.replaceAll("$WORKSPACE", workspace);
            stream.println("SVN Publisher: target: " + target);

            if (null != pomPath) {
                pomPath = pomPath.replaceAll("_WORKSPACE_", workspace);
                pomPath = pomPath.replaceAll("$WORKSPACE", workspace);
                stream.println("SVN Publisher: pomPath: " + pomPath);
            }
        }

        // target directory is required
        File targetDir = new File(target);

        if (!targetDir.canRead()) {
            LOGGER
                .severe("SVNForceImport Error: target Directory not accessable: "
                    + target);
            if (null != stream) {
            stream
                .println("SVN Publisher: Error: target Directory not accessable: "
                    + target);
            }
        }

        // pom is not required, but without it MAJOR/MINOR/PATCH variables won't
        // be available

        SimplePOMParser spp = new SimplePOMParser();
        if (null != pomPath) {

            File pom = new File(pomPath);
            if (!pom.canRead()) {

                LOGGER.severe("SVNForceImport Error: pom File not accessable: "
                    + pomPath);
                if (null != stream) {
                    stream
                        .println("SVN Publisher: Error: pom File not accessable: "
                            + pomPath);
                }
            }
            spp.setMajorPath(majorPath);
            spp.setMinorPath(minorPath);
            spp.setPatchPath(patchPath);
            spp.parse(pom);
        }

        DAVRepositoryFactory.setup();

        SVNRepository repository = null;
        ISVNAuthenticationManager authManager = SVNWCUtil
            .createDefaultAuthenticationManager();

        // create the repo and authManager
        try {
            setupProtocols();
            repository = SVNRepositoryFactory.create(SVNURL
                .parseURIEncoded(svnURL));
            if (null != user) {
                authManager = SVNWCUtil.createDefaultAuthenticationManager(
                    user, password);
            }
            repository.setAuthenticationManager(authManager);

            SVNClientManager ourClientManager = SVNClientManager.newInstance(
                null, repository.getAuthenticationManager());

            // create the commit client that will do the work
            SVNCommitClient commitClient = ourClientManager.getCommitClient();

            SVNUpdateClient updateClient = ourClientManager.getUpdateClient();

            SVNWCClient wcClient = ourClientManager.getWCClient();

            // import each item
            String finalName;
            String finalPath;
            String finalPattern;
            for (ImportItem item : items) {
                // if the pom and major/minor/patch paths have been included
                // attempt to do some simple replacement
                boolean nullName = false;

                finalName = "";
                finalPath = "";
                finalPattern = "";

                if ((null == item.getName()) || (item.getName().length() < 1)) {
                    LOGGER.info("null Name");
                    nullName = true;
                } else {

                    finalName = variableReplace(spp, item.getName());
                }

                finalPattern = variableReplace(spp, item.getPattern());
                finalPath = variableReplace(spp, item.getPath());

                // Added BZ Checkout

                File svnTempDir = new File(target + File.separator + "svntemp"
                    + File.separator
                    + finalPath.replace("/", File.separator));

                if (svnTempDir.exists()) {
                    stream.println("SVN Publisher: update: " + svnURL + "/"
                        + finalPath + " to " + svnTempDir);
                    long revision = updateClient.doUpdate(svnTempDir,
                        SVNRevision.HEAD, SVNDepth.INFINITY, true, true);
                    stream.println("SVN Publisher: revision: " + revision);
                } else {
                    stream.println("SVN Publisher: Checkout: " + svnURL + "/"
                        + finalPath + " to " + svnTempDir);
                    long revision = updateClient.doCheckout(SVNURL
                        .parseURIEncoded(svnURL + "/" + finalPath),
                        svnTempDir, SVNRevision.HEAD, SVNRevision.HEAD,
                        SVNDepth.INFINITY, true);
                    stream.println("SVN Publisher: revision: " + revision);

                }
                // look for files

                ArrayList<String> changed = new ArrayList<String>();

                ArrayList<File> files = matchFiles(finalPattern, targetDir);
                String prefix = "";
                for (int i = 0; i < files.size(); i++) {

                    ensurePath(repository, commitClient, svnURL, finalPath);

                    File file = files.get(i);
                    if (!file.canRead()) {
                        LOGGER
                            .severe("SVNForceImport Error: File/Directory not accessable: "
                                + file.getAbsolutePath());
                        if (null != stream) {
                            stream
                                .println("SVN Publisher: Error: File/Directory not accessable: "
                                    + file.getAbsolutePath());
                        }
                    }

                    if (nullName) {
                        finalName = file.getName();
                    }
                    SVNNodeKind nodeKind = repository.checkPath(finalPath
                        + prefix + finalName, -1);
                    if (nodeKind == SVNNodeKind.NONE) {
                        insertItem(commitClient, svnURL + "/" + finalPath,
                            file, prefix + finalName);
                        if (null != stream) {
                            stream.println("SVN Publisher: Importing Item: "
                                + prefix + finalName);
                        }
                    } else {

                        stream.println("SVN Publisher: Comparing Item: "
                            + prefix + finalName);
                        File snvFile = new File(svnTempDir.getAbsolutePath()
                            + File.separator + finalName);
                        if (!fileContentsEquals(file, snvFile)) {
                            if (copyFile(file, snvFile)) {
                            stream
                                .println("SVN Publisher: File Copied to: "
                                    + snvFile);

                            changed.add(finalName);

                            SVNInfo doInfo = wcClient.doInfo(snvFile,
                                SVNRevision.HEAD);

                            stream.println("SVN Publisher: Info: "
                                + doInfo.getPropTime()
                                + " getCommittedDate: "
                                + doInfo.getCommittedDate()
                                + " getRevision: "
                                + doInfo.getRevision() + " getAuthor: "
                                + doInfo.getAuthor());
                            }
                        }
                        // File[] paths = new File[] { file };
                        // deleteItem(commitClient, svnURL + "/" + finalPath
                        // + prefix + finalName);
                        // if (null != stream) {
                        // stream
                        // .println("SVN Publisher: Deleting Remote Item: "
                        // + prefix + finalName);
                        // }
                        // insertItem(commitClient, svnURL + "/" + finalPath,
                        // files.get(i), prefix + finalName);
                        // if (null != stream) {
                        // stream.println("SVN Publisher: Importing Item: "
                        // + prefix + finalName);
                        // }
                    }

                    // prefix = Integer.toString(i + 1);

                }
                // Temp Path

                File[] svnTempPath = new File[] { svnTempDir };

                SVNInfo doInfo = wcClient.doInfo(svnTempDir, SVNRevision.HEAD);

                stream.println("SVN Publisher: Info: " + doInfo.getPropTime()
                    + " getCommittedDate: " + doInfo.getCommittedDate()
                    + " getRevision: " + doInfo.getRevision()
                    + " getAuthor: " + doInfo.getAuthor());

                SVNCommitPacket ci = commitClient.doCollectCommitItems(
                    svnTempPath, false, true, SVNDepth.INFINITY, null);
                stream.println("SVN Publisher: do Commit: " + ci.toString());

                SVNCommitInfo doCommit = commitClient.doCommit(ci, false,
                    "Jenkins");

                stream.println("SVN Publisher: Commit result: "
                    + doCommit.toString());

            }
        } catch (SVNException svne) {
            stream
                .println("SVN Publisher: Commit result: "
                    + svne.getMessage());
            LOGGER.severe("*SVNForceImport Error: " + svne.getMessage());
        }
    }

    private static boolean copyFile(final File file, final File snvFile) {

        try {
            Files.copy(file, snvFile);
        } catch (FileNotFoundException e) {
            return false;
        } catch (IOException e) {
            return false;
        }
        return true;
    }

    /**
     * Insert a given file/folder into the given repository location with a
     * given name
     * 
     * @param client
     *            The SVNCommitClient to be used to preform the commit action.
     * @param fullURL
     *            The full URL pointing to where the importItem will be placed.
     * @param importItem
     *            The file or folder to be imported in the repository.
     * @param name
     *            The file/folder name to be used in the repository.
     * @return The results of the commit action.
     * @throws SVNException
     */
    private static SVNCommitInfo insertItem(final SVNCommitClient client,
        final String fullURL, final File importItem, final String name)
        throws SVNException {
        String logMessage = "SVNForceImport importing: "
            + importItem.getAbsolutePath();
        return client.doImport(importItem, // File/Directory to be imported
            SVNURL.parseURIEncoded(fullURL + name), // location within svn
            logMessage, // svn comment
            new SVNProperties(), // svn properties
            true, // use global ignores
            false, // ignore unknown node types
            SVNDepth.INFINITY); // fully recursive

    }

    /**
     * Delete a given file/folder from the repository.
     * 
     * @param client
     *            The SVNCommitClient to be used to preform the commit action.
     * @param fullURL
     *            The full URL pointing to the item to be deleted.
     * @return The result of the commit action.
     * @throws SVNException
     */
    private static SVNCommitInfo deleteItem(final SVNCommitClient client,
        final String fullURL) throws SVNException {

        String logMessage = "SVNForceImport removing: " + fullURL;
        SVNURL[] urls = { SVNURL.parseURIEncoded(fullURL) };
        return client.doDelete(urls, logMessage);

    }

    /**
     * Create a given directory in the repository.
     * 
     * @param client
     *            The SVNCommitClient to be used to preform the mkdir action.
     * @param fullPath
     *            The full URL pointing to where the Directory should be created
     *            (including the directory to be created).
     * @return The result of the commit action.
     * @throws SVNException
     */
    private static SVNCommitInfo createDir(final SVNCommitClient client,
        final String fullPath) throws SVNException {

        String logMessage = "SVNForceImport creating Directory : " + fullPath;
        SVNURL[] urls = { SVNURL.parseURIEncoded(fullPath) };
        return client.doMkDir(urls, logMessage);

    }

    /**
     * Set up the different repository protocol factories so that
     * http,https,svn,and file protocols can all be used.
     */
    private static void setupProtocols() {

        // http and https
        DAVRepositoryFactory.setup();
        // svn
        SVNRepositoryFactoryImpl.setup();
        // file
        FSRepositoryFactory.setup();
    }

    /**
     * Search through a given directory and return an ArrayList of any
     * files/folders who's names match the given pattern.
     * 
     * @param patternString
     *            The regular expression pattern to use in matching applicable
     *            file/folder names
     * @param parent
     *            The folder to search for matches in.
     * @return All files/folders matching the given pattern.
     */
    private static ArrayList<File> matchFiles(final String patternString,
        final File parent) {
        Pattern pattern = Pattern.compile(patternString);
        Matcher matcher;
        ArrayList<File> files = new ArrayList<File>();
        for (File file : parent.listFiles()) {
            matcher = pattern.matcher(file.getName());
            if (matcher.matches()) {
                files.add(file);
            }
        }
    return files;

    }

    /**
     * Replace variable names with their values.
     * 
     * @param spp
     *            The SimplePOMParser containing the required variables.
     * @param value
     *            The String to be filtered.
     * @return The new String with variable names replaced with values
     */
    private static String variableReplace(final SimplePOMParser spp,
        String value) {

        value = value.replace("_ROOT_", "");
        value = value.replace("_MAJOR_", Integer.toString(spp.getMajor()));
        value = value.replace("_MINOR_", Integer.toString(spp.getMinor()));
        value = value.replace("_PATCH_", Integer.toString(spp.getPatch()));
        return value;

    }

    /**
     * Validate the the required path exists in the project on the repository.
     * If it doesn't then create it.
     * 
     * @param repository
     *            The repository to be checked.
     * @param commitClient
     *            The SVNCommitClient to be used to preform any commit actions.
     * @param svnURL
     *            The URL of the project in the repository.
     * @param path
     *            The path within the project to be checked/created.
     */
    private static void ensurePath(final SVNRepository repository,
        final SVNCommitClient commitClient, final String svnURL,
        final String path) {
    String[] dirs = path.split("/");
    String constructedPath = "";

    if (dirs.length > 0) {

        for (String dir : dirs) {
        try {
            SVNNodeKind nodeKind = repository.checkPath(constructedPath
                + dir, -1);
            if (nodeKind == SVNNodeKind.NONE) {
            createDir(commitClient, svnURL + "/" + constructedPath
                + dir);
            }
            constructedPath += dir + "/";

        } catch (SVNException svne) {
            System.err.println("SVNForceImport Error: "
                + svne.getMessage());
        }
        }
    }
    }

    private final static int BUFFSIZE = 1024;
    private static byte buff1[] = new byte[BUFFSIZE];
    private static byte buff2[] = new byte[BUFFSIZE];

    public static boolean inputStreamEquals(final InputStream is1,
        final InputStream is2) {
        if (is1 == is2) {
            return true;
        }
        if (is1 == null && is2 == null) {
            return true;
        }
        if (is1 == null || is2 == null) {
            return false;
        }
        try {
            int read1 = -1;
            int read2 = -1;

            do {
                int offset1 = 0;
                while (offset1 < BUFFSIZE
                    && (read1 = is1
                        .read(buff1, offset1, BUFFSIZE - offset1)) >= 0) {
                    offset1 += read1;
                }

                int offset2 = 0;
                while (offset2 < BUFFSIZE
                    && (read2 = is2
                        .read(buff2, offset2, BUFFSIZE - offset2)) >= 0) {
                    offset2 += read2;
                }
                if (offset1 != offset2) {
                    return false;
                }
                if (offset1 != BUFFSIZE) {
                    Arrays.fill(buff1, offset1, BUFFSIZE, (byte) 0);
                    Arrays.fill(buff2, offset2, BUFFSIZE, (byte) 0);
                }
                if (!Arrays.equals(buff1, buff2)) {
                    return false;
                }
            } while (read1 >= 0 && read2 >= 0);
            if (read1 < 0 && read2 < 0) {
                return true; // both at EOF
            }
            return false;

        } catch (Exception ei) {
            return false;
        }
    }

    public static boolean fileContentsEquals(final File file1, final File file2) {
        InputStream is1 = null;
        InputStream is2 = null;
        if (file1.length() != file2.length()) {
            return false;
        }

        try {
            is1 = new FileInputStream(file1);
            is2 = new FileInputStream(file2);

            return inputStreamEquals(is1, is2);

        } catch (Exception ei) {
            return false;
        } finally {
            try {
                if (is1 != null) {
                    is1.close();
                }
                if (is2 != null) {
                    is2.close();
                }
            } catch (Exception ei2) {
            }
        }
    }
}
