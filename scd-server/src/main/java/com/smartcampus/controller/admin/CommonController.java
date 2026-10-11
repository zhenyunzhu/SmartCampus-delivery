package com.smartcampus.controller.admin;

import com.smartcampus.constant.MessageConstant;
import com.smartcampus.dto.CategoryDTO;
import com.smartcampus.result.Result;
import com.smartcampus.utils.AliOssUtil;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;


//通用接口
@RestController
@RequestMapping("/admin/common")
@Api(tags = "通用接口")
@Slf4j
public class CommonController {
    @Autowired
    private AliOssUtil aliOssUtil;

    /**
    * 上传文件
    * @param file
    * @return
     */
    @PostMapping("/upload")
    @ApiOperation("上传文件")
    public Result<String> upload(MultipartFile file) {

        log.info("上传文件：{}", file);
        try {

            String fileName = file.getOriginalFilename();
            //获取文件名后缀
            String extension = fileName.substring(fileName.lastIndexOf("."));
            String fileNameInOss = UUID.randomUUID().toString() + extension;
            //构造文件名称
            String filePath=aliOssUtil.upload(file.getBytes(),fileNameInOss);
            return Result.success(filePath);
        }catch (Exception e){
           log.error("上传文件失败",e);
        }

        return Result.error(MessageConstant.UPLOAD_FAILED);
    }

}
