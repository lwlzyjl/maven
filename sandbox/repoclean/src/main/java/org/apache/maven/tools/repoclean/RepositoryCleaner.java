package org.apache.maven.tools.repoclean;

/*
 * ==================================================================== Copyright 2001-2004 The
 * Apache Software Foundation.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License. ====================================================================
 */

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.construction.ArtifactConstructionSupport;
import org.apache.maven.artifact.metadata.ArtifactMetadata;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.layout.ArtifactRepositoryLayout;
import org.apache.maven.tools.repoclean.artifact.metadata.ProjectMetadata;
import org.apache.maven.tools.repoclean.digest.ArtifactDigestVerifier;
import org.apache.maven.tools.repoclean.discover.ArtifactDiscoverer;
import org.apache.maven.tools.repoclean.index.ArtifactIndexer;
import org.apache.maven.tools.repoclean.report.FileReporter;
import org.apache.maven.tools.repoclean.report.ReportWriteException;
import org.apache.maven.tools.repoclean.report.Reporter;
import org.apache.maven.tools.repoclean.rewrite.ArtifactPomRewriter;
import org.codehaus.plexus.PlexusConstants;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.context.Context;
import org.codehaus.plexus.context.ContextException;
import org.codehaus.plexus.logging.AbstractLogEnabled;
import org.codehaus.plexus.logging.Logger;
import org.codehaus.plexus.mailsender.MailMessage;
import org.codehaus.plexus.mailsender.MailSender;
import org.codehaus.plexus.personality.plexus.lifecycle.phase.Contextualizable;
import org.codehaus.plexus.util.IOUtil;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

/**
 * @author jdcasey
 */
public class RepositoryCleaner
    extends AbstractLogEnabled
    implements Contextualizable
{

    public static final String ROLE = RepositoryCleaner.class.getName();

    private ArtifactDigestVerifier artifactDigestVerifier;

    private ArtifactRepositoryLayout bridgingLayout;

    private MailSender mailSender;

    private ArtifactIndexer artifactIndexer;

    private ArtifactConstructionSupport artifactConstructionSupport = new ArtifactConstructionSupport();

    private PlexusContainer container;

    public void cleanRepository( RepositoryCleanerConfiguration configuration )
        throws Exception
    {
        File reportsBase = normalizeReportsBase( configuration.getReportsPath() );

        File sourceRepositoryBase = normalizeSourceRepositoryBase( configuration.getSourceRepositoryPath() );

        File targetRepositoryBase = normalizeTargetRepositoryBase( configuration.getTargetRepositoryPath() );

        boolean mailReport = false;

        // do not proceed if we cannot produce reports, or if the repository is
        // invalid.
        if ( reportsBase != null && sourceRepositoryBase != null && targetRepositoryBase != null )
        {
            Logger logger = getLogger();

            FileReporter repoReporter = null;
            try
            {
                repoReporter = new FileReporter( reportsBase, "repository.report.txt" );

                ArtifactDiscoverer artifactDiscoverer = null;

                List artifacts = null;
                try
                {
                    artifactDiscoverer = (ArtifactDiscoverer) container.lookup( ArtifactDiscoverer.ROLE, configuration
                        .getSourceRepositoryLayout() );

                    if ( logger.isInfoEnabled() )
                    {
                        logger.info( "Discovering artifacts." );
                    }

                    try
                    {
                        artifacts = artifactDiscoverer.discoverArtifacts( sourceRepositoryBase, repoReporter,
                                                                          configuration.getBlacklistedPatterns() );
                    }
                    catch ( Exception e )
                    {
                        repoReporter.error( "Error discovering artifacts in source repository.", e );
                    }

                }
                finally
                {
                    if ( artifactDiscoverer != null )
                    {
                        container.release( artifactDiscoverer );
                    }
                }

                if ( artifacts != null )
                {
                    ArtifactRepositoryLayout sourceLayout = null;
                    ArtifactRepositoryLayout targetLayout = null;
                    try
                    {
                        sourceLayout = (ArtifactRepositoryLayout) container.lookup( ArtifactRepositoryLayout.ROLE,
                                                                                    configuration
                                                                                        .getSourceRepositoryLayout() );

                        ArtifactRepository sourceRepo = new ArtifactRepository( "source", "file://"
                            + sourceRepositoryBase.getAbsolutePath(), sourceLayout );

                        targetLayout = (ArtifactRepositoryLayout) container.lookup( ArtifactRepositoryLayout.ROLE,
                                                                                    configuration
                                                                                        .getTargetRepositoryLayout() );

                        ArtifactRepository targetRepo = new ArtifactRepository( "target", "file://"
                            + targetRepositoryBase.getAbsolutePath(), targetLayout );

                        if ( logger.isInfoEnabled() )
                        {
                            logger.info( "Rewriting POMs and artifact files." );
                        }

                        artifactIndexer.writeAritfactIndex( artifacts, targetRepositoryBase );

                        rewriteArtifactsAndPoms( artifacts, sourceRepo, targetRepo, configuration, reportsBase,
                                                 sourceRepositoryBase, targetRepositoryBase, repoReporter );
                    }
                    finally
                    {
                        if ( sourceLayout != null )
                        {
                            container.release( sourceLayout );
                        }

                        if ( targetLayout != null )
                        {
                            container.release( targetLayout );
                        }
                    }
                }

                if ( repoReporter.hasError() && logger.isErrorEnabled() )
                {
                    logger.error( "Error encountered while converting source repository to target repository." );
                }

                if ( repoReporter.hasWarning() && logger.isWarnEnabled() )
                {
                    logger
                        .warn( "Warning encountered while rewriting one or more artifacts from source repository to target repository." );
                }

                if ( repoReporter.hasError() )
                {
                    mailReport = true;
                }
            }
            finally
            {
                if ( repoReporter != null )
                {
                    repoReporter.close();
                }
            }

            if ( mailReport && configuration.mailErrorReport() )
            {
                String reportContents = readReportFile( repoReporter.getReportFile() );

                MailMessage message = new MailMessage();
                message.setContent( reportContents );
                message.setSubject( configuration.getErrorReportSubject() );
                message.setFrom( configuration.getErrorReportFromName(), configuration.getErrorReportFromAddress() );
                message.setSendDate( new Date() );
                message.addTo( configuration.getErrorReportToName(), configuration.getErrorReportToAddress() );

                mailSender.send( message );
            }
        }

    }

    private String readReportFile( File reportFile )
        throws IOException
    {
        FileReader reader = null;
        try
        {
            reader = new FileReader( reportFile );

            StringBuffer reportContent = new StringBuffer();
            char[] buffer = new char[512];
            int read = -1;

            while ( ( read = reader.read( buffer ) ) > -1 )
            {
                reportContent.append( buffer, 0, read );
            }

            return reportContent.toString();
        }
        finally
        {
            IOUtil.close( reader );
        }
    }

    private void rewriteArtifactsAndPoms( List artifacts, ArtifactRepository sourceRepo, ArtifactRepository targetRepo,
                                         RepositoryCleanerConfiguration configuration, File reportsBase,
                                         File sourceRepositoryBase, File targetRepositoryBase, FileReporter repoReporter )
        throws Exception
    {
        Logger logger = getLogger();

        ArtifactPomRewriter artifactPomRewriter = null;

        try
        {
            logger.info( "Rewriting up to " + artifacts.size() + " artifacts (Should be " + ( artifacts.size() * 2 )
                + " rewrites including POMs)." );

            int actualRewriteCount = 0;
            for ( Iterator it = artifacts.iterator(); it.hasNext(); )
            {
                Artifact artifact = (Artifact) it.next();

                String artifactReportPath = buildArtifactReportPath( artifact );

                FileReporter artifactReporter = null;
                try
                {
                    artifactReporter = new FileReporter( reportsBase, artifactReportPath );

                    boolean errorOccurred = false;

                    File artifactSource = new File( sourceRepo.getBasedir(), sourceRepo.pathOf( artifact ) );
                    File artifactTarget = new File( targetRepo.getBasedir(), targetRepo.pathOf( artifact ) );

                    artifact.setFile( artifactSource );

                    boolean targetMissingOrOlder = !artifactTarget.exists()
                        || artifactTarget.lastModified() < artifactSource.lastModified();

                    if ( artifactSource.exists() && ( configuration.force() || targetMissingOrOlder ) )
                    {
                        actualRewriteCount++;

                        try
                        {
                            if ( !configuration.reportOnly() )
                            {
                                if ( logger.isDebugEnabled() )
                                {
                                    logger.debug( "sourceRepo basedir is: \'" + sourceRepo.getBasedir() + "\'" );
                                    logger.debug( "targetRepo basedir is: \'" + targetRepo.getBasedir() + "\'" );
                                }

                                File targetParent = artifactTarget.getParentFile();
                                if ( !targetParent.exists() )
                                {
                                    targetParent.mkdirs();
                                }

                                if ( logger.isDebugEnabled() )
                                {
                                    logger.debug( "Copying artifact[" + artifact.getId() + "] from \'" + artifactSource
                                        + "\' to \'" + artifactTarget + "\'." );
                                }

                                copyArtifact( artifact, artifactTarget, artifactReporter );
                            }
                        }
                        catch ( Exception e )
                        {
                            repoReporter.error( "Error transferring artifact[" + artifact.getId()
                                + "] to the target repository.", e );

                            // if we can't copy the jar over, then skip the rest.
                            errorOccurred = true;
                        }

                        if ( !errorOccurred )
                        {
                            if ( logger.isDebugEnabled() )
                            {
                                logger.debug( "working on digest for artifact[" + artifact.getId()
                                    + "] with groupId: \'" + artifact.getGroupId() + "\'" );
                            }

                            try
                            {
                                artifactDigestVerifier.verifyDigest( artifact, artifactTarget, artifactReporter,
                                                                     configuration.reportOnly() );
                            }
                            catch ( Exception e )
                            {
                                repoReporter.error( "Error verifying digest for artifact[" + artifact.getId() + "]", e );
                            }
                        }

                        if ( !errorOccurred )
                        {
                            ArtifactMetadata pom = new ProjectMetadata( artifact );

                            artifactPomRewriter = (ArtifactPomRewriter) container.lookup( ArtifactPomRewriter.ROLE,
                                                                                          configuration
                                                                                              .getSourcePomVersion() );

                            File sourcePom = new File( sourceRepositoryBase, sourceRepo.pathOfMetadata( pom ) );

                            File targetPom = new File( targetRepositoryBase, targetRepo.pathOfMetadata( pom ) );

                            File bridgedTargetPom = new File( targetRepositoryBase, bridgingLayout.pathOfMetadata( pom ) );

                            try
                            {
                                artifactPomRewriter.rewrite( artifact, sourcePom, targetPom, artifactReporter,
                                                             configuration.reportOnly() );

                                bridgePomLocations( targetPom, bridgedTargetPom, artifactReporter );
                            }
                            catch ( Exception e )
                            {
                                repoReporter.error( "Error rewriting POM for artifact[" + artifact.getId()
                                    + "] into the target repository.", e );
                            }
                        }

                    }
                    else if ( !targetMissingOrOlder )
                    {
                        artifactReporter.warn( "Target file for artifact is present and not stale. (Artifact: \'"
                            + artifact.getId() + "\' in path: \'" + artifactSource + "\' with target path: "
                            + artifactTarget + ")." );
                    }
                    else
                    {
                        artifactReporter.error( "Cannot find source file for artifact: \'" + artifact.getId()
                            + "\' under path: \'" + artifactSource + "\'" );
                    }

                    if ( artifactReporter.hasError() )
                    {
                        repoReporter.warn( "Error(s) occurred while rewriting artifact: \'" + artifact.getId()
                            + "\' or its POM." );
                    }
                }
                catch ( Exception e )
                {
                    artifactReporter.error( "Error while rewriting file or POM for artifact: \'" + artifact.getId()
                        + "\'. See report at: \'" + artifactReportPath + "\'.", e );
                }
                finally
                {
                    if ( artifactReporter != null )
                    {
                        artifactReporter.close();
                    }
                }
            }

            logger.info( "Actual number of artifacts rewritten: " + actualRewriteCount + " ("
                + ( actualRewriteCount * 2 ) + " including POMs)." );
        }
        finally
        {
            if ( artifactPomRewriter != null )
            {
                container.release( artifactPomRewriter );
            }
        }
    }

    private void bridgePomLocations( File targetPom, File bridgedTargetPom, Reporter reporter )
        throws IOException, ReportWriteException
    {
        if ( targetPom.equals( bridgedTargetPom ) )
        {
            reporter.warn( "Cannot create legacy-compatible copy of POM at: " + targetPom
                + "; legacy-compatible path is the same as the converted POM itself." );
        }

        FileInputStream in = null;
        FileOutputStream out = null;

        try
        {
            in = new FileInputStream( targetPom );
            out = new FileOutputStream( bridgedTargetPom );

            IOUtil.copy( in, out );
        }
        finally
        {
            IOUtil.close( in );
            IOUtil.close( out );
        }
    }

    private String buildArtifactReportPath( Artifact artifact )
    {
        String classifier = artifact.getClassifier();

        return artifact.getGroupId().replace( '.', '/' ) + "/" + artifact.getArtifactId() + "/" + artifact.getType()
            + "/" + ( ( classifier != null ) ? ( classifier + "-" ) : ( "" ) ) + artifact.getVersion() + ".report.txt";
    }

    private void copyArtifact( Artifact artifact, File artifactTarget, FileReporter reporter )
        throws IOException
    {
        File artifactSource = artifact.getFile();

        InputStream inStream = null;
        OutputStream outStream = null;
        try
        {
            File targetParent = artifactTarget.getParentFile();
            if ( !targetParent.exists() )
            {
                targetParent.mkdirs();
            }

            inStream = new BufferedInputStream( new FileInputStream( artifactSource ) );
            outStream = new BufferedOutputStream( new FileOutputStream( artifactTarget ) );

            byte[] buffer = new byte[16];
            int read = -1;

            while ( ( read = inStream.read( buffer ) ) > -1 )
            {
                outStream.write( buffer, 0, read );
            }

            outStream.flush();
        }
        finally
        {
            IOUtil.close( inStream );
            IOUtil.close( outStream );
        }
    }

    private File normalizeTargetRepositoryBase( String targetRepositoryPath )
    {
        Logger logger = getLogger();

        File targetRepositoryBase = new File( targetRepositoryPath );

        logger.info( "Target repository is at: \'" + targetRepositoryBase + "\'" );

        if ( !targetRepositoryBase.exists() )
        {
            logger.info( "Creating target repository at: \'" + targetRepositoryBase + "\'." );

            targetRepositoryBase.mkdirs();
        }
        else if ( !targetRepositoryBase.isDirectory() )
        {
            logger.error( "Cannot write to target repository \'" + targetRepositoryBase
                + "\' because it is not a directory." );

            targetRepositoryBase = null;
        }

        return targetRepositoryBase;
    }

    private File normalizeSourceRepositoryBase( String sourceRepositoryPath )
    {
        Logger logger = getLogger();

        File sourceRepositoryBase = new File( sourceRepositoryPath );

        logger.info( "Source repository is at: \'" + sourceRepositoryBase + "\'" );

        if ( !sourceRepositoryBase.exists() )
        {
            logger.error( "Cannot convert repository \'" + sourceRepositoryBase + "\' because it does not exist." );

            sourceRepositoryBase = null;
        }
        else if ( !sourceRepositoryBase.isDirectory() )
        {
            logger.error( "Cannot convert repository \'" + sourceRepositoryBase + "\' because it is not a directory." );

            sourceRepositoryBase = null;
        }

        return sourceRepositoryBase;
    }

    private File normalizeReportsBase( String reportsPath )
    {
        Logger logger = getLogger();

        File reportsBase = new File( reportsPath );
        if ( !reportsBase.exists() )
        {
            logger.info( "Creating reports directory: \'" + reportsBase + "\'" );

            reportsBase.mkdirs();
        }
        else if ( !reportsBase.isDirectory() )
        {
            logger.error( "Cannot write reports to \'" + reportsBase + "\' because it is not a directory." );

            reportsBase = null;
        }

        return reportsBase;
    }

    public void contextualize( Context context )
        throws ContextException
    {
        this.container = (PlexusContainer) context.get( PlexusConstants.PLEXUS_KEY );
    }

}