package hudson.plugins.s3;

import com.amazonaws.regions.Regions;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.*;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import hudson.util.CopyOnWriteList;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import javax.servlet.ServletException;
import java.io.IOException;
import java.io.File;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class S3BucketPublisher extends Recorder implements Describable<Publisher> {

    private String profileName;
    @Extension
    public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();

    private final List<Entry> entries = new ArrayList<Entry>();

    /**
     * User metadata key/value pairs to tag the upload with.
     */
    private /*almost final*/ List<MetadataPair> userMetadata = new ArrayList<MetadataPair>();


    @DataBoundConstructor
    public S3BucketPublisher() {
        super();
    }

    public S3BucketPublisher(String profileName) {
        super();
        if (profileName == null) {
            // defaults to the first one
            S3Profile[] sites = DESCRIPTOR.getProfiles();
            if (sites.length > 0)
                profileName = sites[0].getName();
        }
        this.profileName = profileName;
    }

    protected Object readResolve() {
        if (userMetadata==null)
            userMetadata = new ArrayList<MetadataPair>();
        return this;
    }

    public List<Entry> getEntries() {
        return entries;
    }

    public List<MetadataPair> getUserMetadata() {
        return userMetadata;
    }

    public S3Profile getProfile() {
        S3Profile[] profiles = DESCRIPTOR.getProfiles();

        if (profileName == null && profiles.length > 0)
            // default
            return profiles[0];

        for (S3Profile profile : profiles) {
            if (profile.getName().equals(profileName))
                return profile;
        }
        return null;
    }

    public String getName() {
        return this.profileName;
    }

    public void setName(String profileName) {
        this.profileName = profileName;
    }

    protected void log(final PrintStream logger, final String message) {
        logger.println(StringUtils.defaultString(getDescriptor().getDisplayName()) + " " + message);
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build,
                           Launcher launcher,
                           BuildListener listener)
            throws InterruptedException, IOException {

        //TODO: implement a checkbox or dropdown to save a preference for uploading after a failure
        //if (build.getResult() == Result.FAILURE) {
            // build failed. don't post
            //return true;
        //}

        S3Profile profile = getProfile();
        if (profile == null) {
            log(listener.getLogger(), "No S3 profile is configured.");
            build.setResult(Result.UNSTABLE);
            return true;
        }
        log(listener.getLogger(), "Using S3 profile: " + profile.getName());
        try {
            Map<String, String> envVars = build.getEnvironment(listener);

            for (Entry entry : entries) {
                String expanded = Util.replaceMacro(entry.sourceFile, envVars);
                FilePath ws = build.getWorkspace();
                FilePath[] paths = ws.list(expanded);

                if (paths.length == 0) {
                    // try to do error diagnostics
                    log(listener.getLogger(), "No file(s) found: " + expanded);
                    String error = ws.validateAntFileMask(expanded);
                    if (error != null)
                        log(listener.getLogger(), error);
                }

                int searchPathLength = getSearchPathLength(ws.getRemote(), expanded);

                String bucket = Util.replaceMacro(entry.bucket, envVars);
                String storageClass = Util.replaceMacro(entry.storageClass, envVars);
                String selRegion = entry.selectedRegion;
                List<MetadataPair> escapedUserMetadata = new ArrayList<MetadataPair>();
                for (MetadataPair metadataPair : userMetadata) {
                    MetadataPair escapedMetadataPair = new MetadataPair();
                    escapedMetadataPair.key = Util.replaceMacro(metadataPair.key, envVars);
                    escapedMetadataPair.value = Util.replaceMacro(metadataPair.value, envVars);
                    escapedUserMetadata.add(escapedMetadataPair);
                }
                for (FilePath src : paths) {
                    log(listener.getLogger(), "bucket=" + bucket + ", file=" + src.getName() + " region = " + selRegion);
                    profile.upload(bucket, src, searchPathLength, escapedUserMetadata, storageClass, selRegion);
                }
            }
        } catch (IOException e) {
            e.printStackTrace(listener.error("Failed to upload files"));
            build.setResult(Result.UNSTABLE);
        }
        return true;
    }

    private int getSearchPathLength(String workSpace, String filterExpanded) {
        File file1 = new File(workSpace);
        File file2 = new File(file1, filterExpanded);

        String pathWithFilter = file2.getPath();

        int indexOfWildCard = pathWithFilter.indexOf("*");

        if (indexOfWildCard > 0)
        {
            String s = pathWithFilter.substring(0, indexOfWildCard);
            return s.length();
        }
        else
        {
            return pathWithFilter.length();
        }
    }


    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.STEP;
    }

    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {
        private final String[] storageClasses = {"STANDARD", "REDUCED_REDUNDANCY"};

        private final CopyOnWriteList<S3Profile> profiles = new CopyOnWriteList<S3Profile>();
        private static final Logger LOGGER = Logger.getLogger(DescriptorImpl.class.getName());

        public DescriptorImpl(Class<? extends Publisher> clazz) {
            super(clazz);
            load();
        }

        public DescriptorImpl() {
            this(S3BucketPublisher.class);
        }

        @Override
        public String getDisplayName() {
            return "Publish artifacts to S3 Bucket";
        }

        @Override
        public String getHelpFile() {
            return "/plugin/s3/help.html";
        }

        @Override
        public S3BucketPublisher newInstance(StaplerRequest req, net.sf.json.JSONObject formData) throws FormException {
            S3BucketPublisher pub = new S3BucketPublisher();
            req.bindParameters(pub, "s3.");
            pub.getEntries().addAll(req.bindParametersToList(Entry.class, "s3.entry."));
            pub.getUserMetadata().addAll(req.bindParametersToList(MetadataPair.class, "s3.metadataPair."));
            return pub;
        }

        @Override
        public boolean configure(StaplerRequest req, net.sf.json.JSONObject json) throws FormException {
            profiles.replaceBy(req.bindParametersToList(S3Profile.class, "s3."));
            save();
            return true;
        }

        public S3Profile[] getProfiles() {
            return profiles.toArray(new S3Profile[0]);
        }

        public FormValidation doLoginCheck(final StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {
            String name = Util.fixEmpty(req.getParameter("name"));
            if (name == null) {// name is not entered yet
                return FormValidation.ok();

            }
            S3Profile profile = new S3Profile(name, req.getParameter("accessKey"), req.getParameter("secretKey"));

            try {
                profile.check();
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, e.getMessage(), e);
                return FormValidation.error("Can't connect to S3 service: " + e.getMessage());
            }
            return FormValidation.ok();
        }

        public ListBoxModel doFillStorageClassItems() {
            ListBoxModel items = new ListBoxModel();
            for (String clazz : storageClasses) {
                items.add(clazz);
            }
            return items;
        }

        public ListBoxModel doFillBucketRegionItems() {
            ListBoxModel items = new ListBoxModel();
            for (Regions region : Regions.values()) {
                items.add(region.name());
            }
            return items;
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }
    }
}
