package com.github.axet.vget;

import java.io.File;
import java.io.FileNotFoundException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;

import com.github.axet.threads.LimitThreadPool;
import com.github.axet.vget.info.VGetParser;
import com.github.axet.vget.info.VideoFileInfo;
import com.github.axet.vget.info.VideoInfo;
import com.github.axet.vget.info.VideoInfo.States;
import com.github.axet.vget.vhs.VimeoParser;
import com.github.axet.vget.vhs.YouTubeParser;
import com.github.axet.wget.Direct;
import com.github.axet.wget.DirectMultipart;
import com.github.axet.wget.DirectRange;
import com.github.axet.wget.DirectSingle;
import com.github.axet.wget.RetryWrap;
import com.github.axet.wget.info.DownloadInfo;
import com.github.axet.wget.info.DownloadInfo.Part;
import com.github.axet.wget.info.ex.DownloadError;
import com.github.axet.wget.info.ex.DownloadIOCodeError;
import com.github.axet.wget.info.ex.DownloadIOError;
import com.github.axet.wget.info.ex.DownloadInterruptedError;
import com.github.axet.wget.info.ex.DownloadMultipartError;
import com.github.axet.wget.info.ex.DownloadRetry;

public class VGet {
    VideoInfo info;
    // target directory, where we have to download. automatically name files
    // based on video title and conflict files.
    File targetDir;

    // if target file exists, override it. ignores video titles and ignores
    // instead adding (1), (2) ... to filename suffix for conflict files
    // (exists files)
    File targetForce = null;

    /**
     * extract video information constructor
     * 
     * @param source
     *            url source to get video from
     */
    public VGet(URL source) {
        this(source, null);
    }

    public VGet(URL source, File targetDir) {
        this(parser(null, source).info(source), targetDir);
    }

    public VGet(VideoInfo info, File targetDir) {
        this.info = info;
        this.targetDir = targetDir;
    }

    public void setTarget(File file) {
        targetForce = file;
    }

    public void setTargetDir(File targetDir) {
        this.targetDir = targetDir;
    }

    public VideoInfo getVideo() {
        return info;
    }

    public void download() {
        download(null, new AtomicBoolean(false), new Runnable() {
            @Override
            public void run() {
            }
        });
    }

    public void download(VGetParser user) {
        download(user, new AtomicBoolean(false), new Runnable() {
            @Override
            public void run() {
            }
        });
    }

    /**
     * Drop all forbidden characters from filename
     * 
     * @param f
     *            input file name
     * @return normalized file name
     */
    static String replaceBadChars(String f) {
        String replace = " ";
        f = f.replaceAll("/", replace);
        f = f.replaceAll("\\\\", replace);
        f = f.replaceAll(":", replace);
        f = f.replaceAll("\\?", replace);
        f = f.replaceAll("\\\"", replace);
        f = f.replaceAll("\\*", replace);
        f = f.replaceAll("<", replace);
        f = f.replaceAll(">", replace);
        f = f.replaceAll("\\|", replace);
        f = f.trim();
        f = StringUtils.removeEnd(f, ".");
        f = f.trim();

        String ff;
        while (!(ff = f.replaceAll("  ", " ")).equals(f)) {
            f = ff;
        }

        return f;
    }

    static String maxFileNameLength(String str) {
        int max = 255;
        if (str.length() > max)
            str = str.substring(0, max);
        return str;
    }

    boolean done(AtomicBoolean stop) {
        if (stop.get())
            throw new DownloadInterruptedError("stop");
        if (Thread.currentThread().isInterrupted())
            throw new DownloadInterruptedError("interrupted");

        return false;
    }

    VideoFileInfo getNewInfo(List<VideoFileInfo> infoList, VideoFileInfo infoOld) {
        if (infoOld == null)
            return null;

        for (VideoFileInfo d : infoList) {
            if (infoOld.resume(d))
                return d;
        }

        return null;
    }

    void retry(VGetParser user, AtomicBoolean stop, Runnable notify, Throwable e) {
        boolean retracted = false;

        while (!retracted) {
            for (int i = RetryWrap.RETRY_DELAY; i >= 0; i--) {
                if (stop.get())
                    throw new DownloadInterruptedError("stop");
                if (Thread.currentThread().isInterrupted())
                    throw new DownloadInterruptedError("interrupted");

                info.setRetrying(i, e);
                notify.run();

                try {
                    Thread.sleep(RetryWrap.RETRY_SLEEP);
                } catch (InterruptedException ee) {
                    throw new DownloadInterruptedError(ee);
                }
            }

            try {
                // if we continue to download from old source, and this
                // proxy server is down we have to try to extract new info
                // and try to resume download

                List<VideoFileInfo> infoOldList = info.getInfo();

                user = parser(user, info.getWeb());
                user.info(info, stop, notify);

                if (infoOldList != null) {
                    // info replaced by user.info() call
                    List<VideoFileInfo> infoNewList = info.getInfo();

                    for (VideoFileInfo infoOld : infoOldList) {
                        DownloadInfo infoNew = getNewInfo(infoNewList, infoOld);

                        if (infoOld != null && infoNew != null && infoOld.resume(infoNew)) {
                            infoNew.copy(infoOld);
                        } else {
                            if (infoOld.targetFile != null) {
                                FileUtils.deleteQuietly(infoOld.targetFile);
                                infoOld.targetFile = null;
                            }
                        }

                        retracted = true;
                    }
                }
            } catch (DownloadIOCodeError ee) {
                if (retry(ee)) {
                    info.setState(States.RETRYING, ee);
                    notify.run();
                } else {
                    throw ee;
                }
            } catch (DownloadRetry ee) {
                info.setState(States.RETRYING, ee);
                notify.run();
            }
        }
    }

    // return ".ext" ex: ".mp3" ".webm"
    String getExt(DownloadInfo dinfo) {
        String ct = dinfo.getContentType();
        if (ct == null)
            throw new DownloadRetry("null content type");

        // for single file download keep only extension
        ct = ct.replaceFirst("video/", "");
        ct = ct.replaceFirst("audio/", "");

        return "." + ct.replaceAll("x-", "").toLowerCase();
    }

    // return ".content.ext" ex: ".audio.mp3"
    String getContentExt(DownloadInfo dinfo) {
        String ct = dinfo.getContentType();
        if (ct == null)
            throw new DownloadRetry("null content type");

        // for multi file download keep content type and extension. some video can have same extensions for booth
        // audio/video streams
        ct = ct.replaceFirst("/", ".");

        return "." + ct.replaceAll("x-", "").toLowerCase();
    }

    boolean exists(File f) {
        for (VideoFileInfo dinfo : info.getInfo()) {
            if (dinfo.targetFile != null && dinfo.targetFile.equals(f))
                return true;
        }
        return false;
    }

    void targetFileForce(VideoFileInfo dinfo) {
        if (targetForce != null) {
            dinfo.targetFile = targetForce;

            // should we force to delete target file instead = null? seems so.
            if (dinfo.multipart()) {
                if (!DirectMultipart.canResume(dinfo, dinfo.targetFile))
                    dinfo.targetFile = null;
            } else if (dinfo.getRange()) {
                if (!DirectRange.canResume(dinfo, dinfo.targetFile))
                    dinfo.targetFile = null;
            } else {
                if (!DirectSingle.canResume(dinfo, dinfo.targetFile))
                    dinfo.targetFile = null;
            }
        }
    }

    // return true, video download have the same ".ext" for multiple videos
    boolean targetFileExt(VideoFileInfo dinfo, String ext) {
        if (dinfo.targetFile == null) {
            if (targetDir == null) {
                throw new RuntimeException("Set download file or directory first");
            }

            boolean conflict = false;

            File f;

            Integer idupcount = 0;

            String sfilename = replaceBadChars(info.getTitle());

            sfilename = maxFileNameLength(sfilename);

            boolean c = false;
            do {
                // add = " (1)"
                String add = idupcount > 0 ? " (".concat(idupcount.toString()).concat(")") : "";
                f = new File(targetDir, sfilename + add + ext);
                idupcount += 1;
                c = exists(f);
                conflict |= c;
            } while (f.exists() || c);

            dinfo.targetFile = f;

            // if we don't have resume file (targetForce==null) then we shall
            // start over.
            dinfo.reset();

            return conflict;
        }
        return false;
    }

    void targetFile(VideoFileInfo dinfo) {
        targetFileForce(dinfo);

        targetFileExt(dinfo, getExt(dinfo));
    }

    boolean retry(Throwable e) {
        if (e == null)
            return true;

        if (e instanceof DownloadIOCodeError) {
            DownloadIOCodeError c = (DownloadIOCodeError) e;
            switch (c.getCode()) {
            case HttpURLConnection.HTTP_FORBIDDEN:
            case 416: // 416 Requested Range Not Satisfiable
                return true;
            default:
                return false;
            }
        }

        return false;
    }

    /**
     * @return return status of download information. subclassing for VideoInfo.empty();
     * 
     */
    public boolean empty() {
        return getVideo().empty();
    }

    public void extract() {
        extract(new AtomicBoolean(false), new Runnable() {
            @Override
            public void run() {
            }
        });
    }

    public void extract(AtomicBoolean stop, Runnable notify) {
        extract(null, stop, notify);
    }

    /**
     * extract video information, retry until success
     * 
     * @param user
     *            user info object
     * @param stop
     *            stop signal boolean
     * @param notify
     *            notify executre
     */
    public void extract(VGetParser user, AtomicBoolean stop, Runnable notify) {
        try {
            while (!done(stop)) {
                try {
                    if (info.empty()) {
                        info.setState(States.EXTRACTING);
                        user = parser(user, info.getWeb());
                        user.info(info, stop, notify);
                        info.setState(States.EXTRACTING_DONE);
                        notify.run();
                    }
                    return;
                } catch (DownloadRetry e) {
                    retry(user, stop, notify, e);
                } catch (DownloadMultipartError e) {
                    checkFileNotFound(e);
                    checkRetry(e);
                    retry(user, stop, notify, e);
                } catch (DownloadIOCodeError e) {
                    if (retry(e))
                        retry(user, stop, notify, e);
                    else
                        throw e;
                } catch (DownloadIOError e) {
                    retry(user, stop, notify, e);
                }
            }
        } catch (DownloadInterruptedError e) {
            info.setState(States.STOP);
            notify.run();
            throw e;
        }
    }

    void checkRetry(DownloadMultipartError e) {
        for (Part ee : e.getInfo().getParts()) {
            if (!retry(ee.getException())) {
                throw e;
            }
        }
    }

    /**
     * check if all parts has the same filenotfound exception. if so throw DownloadError.FilenotFoundexcepiton
     * 
     * @param e
     *            error occured
     */
    void checkFileNotFound(DownloadMultipartError e) {
        FileNotFoundException f = null;
        for (Part ee : e.getInfo().getParts()) {
            // no error for this part? skip it
            if (ee.getException() == null)
                continue;
            // this exception has no cause? then it is not FileNotFound
            // excpetion. then do noting. this is checking function. do not
            // rethrow
            if (ee.getException().getCause() == null)
                return;
            if (ee.getException().getCause() instanceof FileNotFoundException) {
                // our first filenotfoundexception?
                if (f == null) {
                    // save it for later checks
                    f = (FileNotFoundException) ee.getException().getCause();
                } else {
                    // check filenotfound error message is it the same?
                    FileNotFoundException ff = (FileNotFoundException) ee.getException().getCause();
                    if (!ff.getMessage().equals(f.getMessage())) {
                        // if the filenotfound exception message is not the
                        // same. then we cannot retrhow filenotfound exception.
                        // return and continue checks
                        return;
                    }
                }
            } else {
                break;
            }
        }
        if (f != null)
            throw new DownloadError(f);
    }

    public void download(final AtomicBoolean stop, final Runnable notify) {
        download(null, stop, notify);
    }

    public void download(VGetParser user, final AtomicBoolean stop, final Runnable notify) {
        try {
            if (empty()) {
                extract(user, stop, notify);
            }

            while (!done(stop)) {
                try {
                    final List<VideoFileInfo> dinfoList = info.getInfo();

                    final LimitThreadPool l = new LimitThreadPool(4);

                    final Thread main = Thread.currentThread();

                    // safety checks. should it be 'vhs' dependent? does other services return other than "video/audio"?
                    for (final VideoFileInfo dinfo : dinfoList) {
                        {
                            String c = dinfo.getContentType();
                            if (c == null)
                                c = "";
                            boolean v = c.contains("video/");
                            boolean a = c.contains("audio/");
                            if (!v && !a) {
                                throw new DownloadRetry(
                                        "unable to download video, bad content " + dinfo.getContentType());
                            }
                        }
                    }

                    // new targetFile() call
                    {
                        boolean conflict = false;
                        // 1) ".ext"
                        for (final VideoFileInfo dinfo : dinfoList) {
                            dinfo.targetFile = null;
                            targetFileForce(dinfo);
                            conflict |= targetFileExt(dinfo, getExt(dinfo));
                        }
                        // conflict means we have " (1).ext" download. try add ".content.ext" as extension
                        // to make file names looks more pretty.
                        if (conflict) {
                            // 2) ".content.ext"
                            for (final VideoFileInfo dinfo : dinfoList) {
                                dinfo.targetFile = null;
                                targetFileForce(dinfo);
                                targetFileExt(dinfo, getContentExt(dinfo));
                            }
                        }
                    }

                    for (final VideoFileInfo dinfo : dinfoList) {
                        if (dinfo.targetFile == null) {
                            throw new RuntimeException("bad target");
                        }

                        Direct directV;

                        if (dinfo.multipart()) {
                            // multi part? overwrite.
                            directV = new DirectMultipart(dinfo, dinfo.targetFile);
                        } else if (dinfo.getRange()) {
                            // range download? try to resume download from last
                            // position
                            if (dinfo.targetFile.exists() && dinfo.targetFile.length() != dinfo.getCount())
                                dinfo.targetFile = null;
                            directV = new DirectRange(dinfo, dinfo.targetFile);
                        } else {
                            // single download? overwrite file
                            directV = new DirectSingle(dinfo, dinfo.targetFile);
                        }
                        final Direct direct = directV;

                        final Runnable r = new Runnable() {
                            @Override
                            public void run() {
                                switch (dinfo.getState()) {
                                case DOWNLOADING:
                                    info.setState(States.DOWNLOADING);
                                    notify.run();
                                    break;
                                case RETRYING:
                                    info.setRetrying(dinfo.getDelay(), dinfo.getException());
                                    notify.run();
                                    break;
                                default:
                                    // we can safely skip all statues.
                                    // (extracting - already passed, STOP /
                                    // ERROR / DONE i will catch up here
                                }
                            }
                        };

                        try {
                            l.blockExecute(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        direct.download(stop, r);
                                    } catch (DownloadInterruptedError e) {
                                        // we need to handle this task error to l.waitUntilTermination()
                                        main.interrupt();
                                    }
                                }
                            });
                        } catch (InterruptedException e) {
                            l.interrupt();
                            // wait for childs to exit
                            boolean clear = true;
                            while (clear) {
                                try {
                                    l.join();
                                    clear = false;
                                } catch (InterruptedException ee) {
                                    // we got interrupted twice from main.interrupt()
                                }
                            }
                            throw new DownloadInterruptedError(e);
                        }
                    }

                    try {
                        l.waitUntilTermination();
                    } catch (InterruptedException e) {
                        l.interrupt();
                        // wait for childs to exit
                        boolean clear = true;
                        while (clear) {
                            try {
                                l.join();
                                clear = false;
                            } catch (InterruptedException ee) {
                                // we got interrupted twice from main.interrupt()
                            }
                        }
                        throw new DownloadInterruptedError(e);
                    }

                    info.setState(States.DONE);
                    notify.run();
                    // break while()
                    return;
                } catch (DownloadRetry e) {
                    retry(user, stop, notify, e);
                } catch (DownloadMultipartError e) {
                    checkFileNotFound(e);
                    checkRetry(e);
                    retry(user, stop, notify, e);
                } catch (DownloadIOCodeError e) {
                    if (retry(e))
                        retry(user, stop, notify, e);
                    else
                        throw e;
                } catch (DownloadIOError e) {
                    retry(user, stop, notify, e);
                }
            }
        } catch (DownloadInterruptedError e) {
            info.setState(VideoInfo.States.STOP, e);
            notify.run();
            throw e;
        } catch (RuntimeException e) {
            info.setState(VideoInfo.States.ERROR, e);
            notify.run();
            throw e;
        }
    }

    public static VGetParser parser(URL web) {
        return parser(null, web);
    }

    public static VGetParser parser(VGetParser user, URL web) {
        VGetParser ei = user;

        if (ei == null && YouTubeParser.probe(web))
            ei = new YouTubeParser();

        if (ei == null && VimeoParser.probe(web))
            ei = new VimeoParser();

        if (ei == null)
            throw new RuntimeException("unsupported web site");

        return ei;
    }

}
