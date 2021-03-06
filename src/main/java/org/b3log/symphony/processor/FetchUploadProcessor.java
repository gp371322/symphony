/*
 * Symphony - A modern community (forum/BBS/SNS/blog) platform written in Java.
 * Copyright (C) 2012-2019, b3log.org & hacpai.com
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.b3log.symphony.processor;

import com.qiniu.storage.Configuration;
import com.qiniu.storage.UploadManager;
import com.qiniu.util.Auth;
import jodd.http.HttpRequest;
import jodd.http.HttpResponse;
import jodd.net.MimeTypes;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.b3log.latke.Latkes;
import org.b3log.latke.ioc.Inject;
import org.b3log.latke.logging.Level;
import org.b3log.latke.logging.Logger;
import org.b3log.latke.servlet.HttpMethod;
import org.b3log.latke.servlet.RequestContext;
import org.b3log.latke.servlet.annotation.After;
import org.b3log.latke.servlet.annotation.Before;
import org.b3log.latke.servlet.annotation.RequestProcessing;
import org.b3log.latke.servlet.annotation.RequestProcessor;
import org.b3log.latke.util.Strings;
import org.b3log.symphony.model.Common;
import org.b3log.symphony.processor.advice.LoginCheck;
import org.b3log.symphony.processor.advice.stopwatch.StopwatchEndAdvice;
import org.b3log.symphony.processor.advice.stopwatch.StopwatchStartAdvice;
import org.b3log.symphony.service.OptionQueryService;
import org.b3log.symphony.util.Networks;
import org.b3log.symphony.util.Symphonys;
import org.json.JSONObject;

import javax.servlet.http.HttpServletResponse;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.URL;
import java.util.UUID;

/**
 * Fetch file and upload processor.
 * <p>
 * <ul>
 * <li>Fetches the remote file and upload it (/fetch-upload), POST</li>
 * </ul>
 * </p>
 *
 * @author <a href="http://88250.b3log.org">Liang Ding</a>
 * @version 1.0.1.0, Jan 22, 2019
 * @since 1.5.0
 */
@RequestProcessor
public class FetchUploadProcessor {

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(FetchUploadProcessor.class);

    /**
     * Option query service.
     */
    @Inject
    private OptionQueryService optionQueryService;

    /**
     * Fetches the remote file and upload it.
     *
     * @param context the specified context
     */
    @RequestProcessing(value = "/fetch-upload", method = HttpMethod.POST)
    @Before({StopwatchStartAdvice.class, LoginCheck.class})
    @After({StopwatchEndAdvice.class})
    public void fetchUpload(final RequestContext context) {
        context.renderJSON();

        final JSONObject requestJSONObject = context.requestJSON();
        final String originalURL = requestJSONObject.optString(Common.URL);
        if (!Strings.isURL(originalURL) || !StringUtils.startsWithIgnoreCase(originalURL, "http")) {
            return;
        }

        HttpResponse res = null;
        byte[] data;
        String contentType;
        try {
            final String host = new URL(originalURL).getHost();
            final String hostIp = InetAddress.getByName(host).getHostAddress();
            if (Networks.isInnerAddress(hostIp)) {
                return;
            }

            final HttpRequest req = HttpRequest.get(originalURL).header(Common.USER_AGENT, Symphonys.USER_AGENT_BOT).method("GET");
            res = req.send();

            if (HttpServletResponse.SC_OK != res.statusCode()) {
                return;
            }

            data = res.bodyBytes();
            contentType = res.contentType();
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Fetch file [url=" + originalURL + "] failed", e);

            return;
        } finally {
            if (null != res) {
                try {
                    res.close();
                } catch (final Exception e) {
                    LOGGER.log(Level.ERROR, "Close response failed", e);
                }
            }
        }

        String suffix;
        String[] exts = MimeTypes.findExtensionsByMimeTypes(contentType, false);
        if (null != exts && 0 < exts.length) {
            suffix = exts[0];
        } else {
            suffix = StringUtils.substringAfter(contentType, "/");
        }

        final String[] allowedSuffixArray = Symphonys.get("upload.suffix").split(",");
        if (!Strings.containsIgnoreCase(suffix, allowedSuffixArray)) {
            return;
        }

        String fileName = UUID.randomUUID().toString().replace("-", "") + "." + suffix;

        if (FileUploadProcessor.QN_ENABLED) {
            final Auth auth = Auth.create(Symphonys.get("qiniu.accessKey"), Symphonys.get("qiniu.secretKey"));
            final UploadManager uploadManager = new UploadManager(new Configuration());

            try {
                uploadManager.put(data, "e/" + fileName, auth.uploadToken(Symphonys.get("qiniu.bucket")),
                        null, contentType, false);
            } catch (final Exception e) {
                LOGGER.log(Level.ERROR, "Uploads to qiniu failed", e);
            }

            context.renderJSONValue(Common.URL, Symphonys.get("qiniu.domain") + "/e/" + fileName);
            context.renderJSONValue("originalURL", originalURL);
        } else {
            fileName = FileUploadProcessor.genFilePath(fileName);
            try (final OutputStream output = new FileOutputStream(FileUploadProcessor.UPLOAD_DIR + fileName)) {
                IOUtils.write(data, output);
            } catch (final Exception e) {
                LOGGER.log(Level.ERROR, "Writes output stream failed", e);
            }

            context.renderJSONValue(Common.URL, Latkes.getServePath() + "/upload/" + fileName);
            context.renderJSONValue("originalURL", originalURL);
        }

        context.renderTrueResult();
    }
}
