/**
 * 上传并绑定附件到附件面板示例。
 * <p>
 * 适用插件：表单插件、操作插件、服务层
 * 优先封装：AttachmentUtils、AttPanelUploader
 * 原生兜底：AttachmentServiceHelper、附件面板原生控件 API
 * 相关 lint 规则：STYLE-007、RESOURCE-001、RESOURCE-004
 * <p>
 * 使用场景：
 * 1. 已拿到第三方文件 URL、byte[] 或 InputStream，需要挂到当前单据附件面板；
 * 2. 后台接口建单成功后，继续补传电子回单、影像件、证照等附件；
 * 3. 希望走仓库推荐链路：AttPanelUploader 上传，再 bindTo(...) 绑定。
 * 4. 需要控制同名覆盖或大小校验策略。
 */
package kd.cd.common.snippets.attachment;

import kd.bos.dataentity.resource.ResManager;
import kd.bos.exception.ErrorCode;
import kd.bos.exception.KDBizException;
import kd.bos.logging.Log;
import kd.bos.logging.LogFactory;
import kd.cd.common.attachment.AttachmentUtils;
import kd.cd.common.attachment.upload.AttPanelUploader;
import kd.cd.common.attachment.upload.PanelUploadResult;
import kd.cd.core.tuple.State;

import java.io.IOException;
import java.io.InputStream;

public class AttachmentUploadBindSample {
    private static final Log log = LogFactory.getLog(AttachmentUploadBindSample.class);
    private static final String TARGET_FORM_ID = "kdcd_basicapplication";
    private static final String ATTACHMENT_PANEL_KEY = "attachmentpanel";
    private static final String RES_APP_ID = "kd-cd-common-snippets";
    private static final String ERR_CODE_ATTACHMENT = "AttachmentUploadBindSample_001";

    // --- 已有文件 URL：先下载，再上传并绑定 ---
    public static State uploadUrlToPanel(Object pkValue, String fileUrl, String fileName) {
        try (InputStream in = AttachmentUtils.download(fileUrl)) {
            if (in == null) {
                return State.of(false, ResManager.loadKDString("附件下载失败或文件为空", "AttachmentUploadBindSample_0", RES_APP_ID));
            }
            return AttPanelUploader.of(TARGET_FORM_ID, pkValue)
                    .upload(in, fileName)
                    .bindTo(ATTACHMENT_PANEL_KEY);
        } catch (IOException e) {
            log.error(ResManager.loadKDString("附件下载异常", "AttachmentUploadBindSample_1", RES_APP_ID), e);
            return State.of(false, String.format(
                    ResManager.loadKDString("附件下载异常：%s", "AttachmentUploadBindSample_2", RES_APP_ID),
                    e.getMessage()
            ));
        }
    }

    // --- 已有 byte[]：适合接口返回文件流、SFTP 下载结果、报表导出结果 ---
    public static void uploadBytesToPanelOrThrow(Object pkValue, byte[] bytes, String fileName) {
        if (bytes == null || bytes.length == 0) {
            throw new KDBizException(new ErrorCode(
                    ERR_CODE_ATTACHMENT,
                    ResManager.loadKDString("上传附件不能为空", "AttachmentUploadBindSample_3", RES_APP_ID)
            ));
        }
        State state = AttPanelUploader.of(TARGET_FORM_ID, pkValue)
                .upload(bytes, fileName)
                .bindTo(ATTACHMENT_PANEL_KEY);
        if (!state.isTrue()) {
            throw new KDBizException(new ErrorCode(
                    ERR_CODE_ATTACHMENT,
                    String.format(
                            ResManager.loadKDString("附件上传绑定失败：%s", "AttachmentUploadBindSample_4", RES_APP_ID),
                            state.text()
                    )
            ));
        }
    }

    // --- 已有 InputStream：适合页面临时区文件、网关下载流、OSS/SFTP 输入流 ---
    public static State uploadStreamToPanel(Object pkValue, InputStream inputStream, String fileName) {
        return AttPanelUploader.of(TARGET_FORM_ID, pkValue)
                .upload(inputStream, fileName)
                .bindTo(ATTACHMENT_PANEL_KEY);
    }

    // --- 需要控制上传参数：覆盖同名文件、跳过大小校验 ---
    public static State uploadWithOptions(Object pkValue, byte[] bytes, String fileName, boolean overrideExistFile, boolean skipUploadSizeCheck) {
        AttPanelUploader uploader = AttPanelUploader.of(TARGET_FORM_ID, pkValue);
        uploader.setOverrideExistFile(overrideExistFile);
        uploader.setSkipUploadSizeCheck(skipUploadSizeCheck);

        PanelUploadResult result = uploader.upload(bytes, fileName);
        return result.bindTo(ATTACHMENT_PANEL_KEY);
    }
}
