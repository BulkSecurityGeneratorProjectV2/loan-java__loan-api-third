package com.mod.loan.service.impl.rongze;

import com.alibaba.fastjson.JSONObject;
import com.mod.loan.common.enums.UserOriginEnum;
import com.mod.loan.common.exception.BizException;
import com.mod.loan.common.model.RequestThread;
import com.mod.loan.common.model.ResponseBean;
import com.mod.loan.common.model.ResultMap;
import com.mod.loan.config.Constant;
import com.mod.loan.mapper.*;
import com.mod.loan.model.*;
import com.mod.loan.service.UserService;
import com.mod.loan.util.Base64Util;
import com.mod.loan.util.aliyun.OSSUtil;
import com.mod.loan.util.rongze.RongZeRequestUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * 推送用户补充信息
 */
@Slf4j
@Component
public class UserInfoAdditRequestHandler {

    @Resource
    UserService userService;
    @Resource
    UserMapper userMapper;
    @Resource
    UserIdentMapper userIdentMapper;
    @Resource
    UserInfoMapper userInfoMapper;
    @Resource
    private MoxieMobileMapper moxieMobileMapper;


    //推送用户补充信息
    @Transactional
    public ResponseBean<Map<String, Object>> userInfoAddit(JSONObject param) throws BizException {
        Map<String, Object> map = new HashMap<>();
        String message="成功";
        JSONObject bizData =  JSONObject.parseObject(param.getString("biz_data"));
        log.info("===============推送用户补充信息开始====================" + bizData.toJSONString());
        //机构定制信息
        String orderNo=bizData.getString("order_no");
        String ID_Positive=bizData.getJSONArray("ID_Positive").getString(0);
        String ID_Negative=bizData.getJSONArray("ID_Negative").getString(0);
//        String photo_hand_ID=bizData.getJSONArray("photo_hand_ID").getString(0);
        String photo_assay=bizData.getJSONArray("photo_assay").getString(bizData.getJSONArray("photo_assay").size()-1);
        String addr_detail=bizData.getString("addr_detail");
//        String family_live_type=bizData.getString("family_live_type");
//        String household_type=bizData.getString("household_type");
        String user_email=bizData.getString("user_email");
        String user_marriage=bizData.getString("user_marriage");
//        String loan_use=bizData.getString("loan_use");
//        String credite_status=bizData.getString("credite_status");
//        String asset_auto_type=bizData.getString("asset_auto_type");
        String contact1A_relationship=bizData.getString("contact1A_relationship");
//        String contact1A_name=bizData.getString("contact1A_name");
        String contact1A_number=bizData.getString("contact1A_number");
        String emergency_contact_personA_relationship=bizData.getString("emergency_contact_personA_relationship");
        String emergency_contact_personA_name=bizData.getString("emergency_contact_personA_name");
        String emergency_contact_personA_phone=bizData.getString("emergency_contact_personA_phone");
        String company_name=bizData.getString("company_name");
        String company_addr_detail=bizData.getString("company_addr_detail");
        String company_number=bizData.getString("company_number");
//        String company_type=bizData.getString("company_type");
        String position=bizData.getString("position");
//        String hiredate=bizData.getString("hiredate");
//        String income_type=bizData.getString("income_type");
        String industry_type=bizData.getString("industry_type");
//        String amount_of_staff=bizData.getString("amount_of_staff");
//        String ext=bizData.getString("ext");
        //开始新增
        User user = userService.selectByPrimaryKey(RequestThread.getUid());
        if (user == null) throw new BizException("推送用户补充信息:用户不存在");

        this.upLoadUserIdcard(orderNo,user,ID_Positive,ID_Negative,photo_assay,"");
        this.checkPhone(orderNo,user);
        //更新用户信息
        user.setUserEmail(user_email);
        int userN = userMapper.updateByPrimaryKey(user);
        if(userN ==0) throw new RuntimeException("推送用户补充信息:用户更新失败");

        UserInfo userInfo = userInfoMapper.selectByPrimaryKey(RequestThread.getUid());
        if (userInfo == null) throw new BizException("推送用户补充信息:用户详细信息不存在");

//        userInfo.setLiveProvince(addr_detail.split(" ")[0]);
//        userInfo.setLiveCity(addr_detail.split(" ")[1]);
//        userInfo.setLiveAddress(addr_detail);
        userInfo.setLiveMarry(user_marriage);
//        userInfo.setWorkCompanyProvince(company_addr_detail.split(" ")[0]);
//        userInfo.setWorkCompanyCity(company_addr_detail.split(" ")[1]);
//        userInfo.setWorkCompanyArea(company_addr_detail.split(" ")[2]);
        userInfo.setWorkAddress(company_addr_detail);
        userInfo.setWorkCompany(company_name);
        userInfo.setWorkCompanyPhone(company_number);
        userInfo.setWorkType(this.getSrly(industry_type));
        userInfo.setJobTitle(this.getCompanyGw(position));
        userInfo.setDirectContact(this.getlxr(contact1A_relationship));
        userInfo.setDirectContactPhone(contact1A_number);
        userInfo.setDirectContactName(contact1A_number);
        userInfo.setOthersContact(this.getlxr(emergency_contact_personA_relationship));
        userInfo.setOthersContactName(emergency_contact_personA_name);
        userInfo.setOthersContactPhone(emergency_contact_personA_phone);
        int userInfoN = userInfoMapper.updateByPrimaryKey(userInfo);
        if(userInfoN ==0) throw new RuntimeException("推送用户补充信息:用户详情信息更新失败");

        UserIdent userIdent = userIdentMapper.selectByPrimaryKey(RequestThread.getUid());
        if (userIdent == null) throw new BizException("推送用户补充信息:用户认证信息不存在");

        userIdent.setUserDetails(2);
        userIdent.setUserDetailsTime(new Date());
        userIdent.setRealName(2);
        userIdent.setRealNameTime(new Date());
        userIdent.setMobile(2);
        userIdent.setMobileTime(new Date());
        int userIdentN = userIdentMapper.updateByPrimaryKey(userIdent);
        if(userIdentN ==0) throw new RuntimeException("推送用户补充信息:用户认证信息更新失败");
        log.info("===============推送用户补充信息结束====================");
        return ResponseBean.success(map);
    }

    /**
     * 上传身份证文件，并保存到user上
     * @param orderNo
     * @param user
     * @param str1 身份证正面
     * @param str2 身份证反面
     * @param str3 活体认证
     * @param str4 手持拍照
     * @return
     * @throws BizException
     */
    public boolean upLoadUserIdcard(String orderNo,User user,String str1,String str2,String str3,String str4) throws BizException {
        boolean flag = false;
        try {
            JSONObject jsonObject1=new JSONObject();
            jsonObject1.put("order_no",orderNo);
            jsonObject1.put("fileid",str1);
            String result1 = RongZeRequestUtil.doPost(Constant.rongZeQueryUrl, "api.resource.findfile", jsonObject1.toJSONString());
            log.info("推送用户补充信息:身份证正面信息：" + result1);
            JSONObject resultJson1 = JSONObject.parseObject(result1);
            if(!resultJson1.containsKey("code") || !resultJson1.containsKey("data") || resultJson1.getInteger("code") != 200){
                throw new BizException("推送用户补充信息:身份证正面信息解析失败"  + result1);
            }

            String imgCertFront = OSSUtil.uploadImage(Base64Util.decode(result1.getBytes()));
            user.setImgCertFront(imgCertFront);

            JSONObject jsonObject2=new JSONObject();
            jsonObject2.put("order_no",orderNo);
            jsonObject2.put("fileid",str2);
            String result2 = RongZeRequestUtil.doPost(Constant.rongZeQueryUrl, "api.resource.findfile", jsonObject2.toJSONString());
            log.info("推送用户补充信息:身份证背面信息：" + result2);
            JSONObject resultJson2 = JSONObject.parseObject(result2);
            if(!resultJson2.containsKey("code") || !resultJson2.containsKey("data") || resultJson2.getInteger("code") != 200){
                throw new BizException("推送用户补充信息:身份证背面信息解析失败" + result2);
            }
            String imgCertBack = OSSUtil.uploadImage(Base64Util.decode(result2.getBytes()));
            user.setImgCertBack(imgCertBack);

            JSONObject jsonObject3=new JSONObject();
            jsonObject3.put("order_no",orderNo);
            jsonObject3.put("fileid",str1);
            String result3 = RongZeRequestUtil.doPost(Constant.rongZeQueryUrl, "api.resource.findfile", jsonObject3.toJSONString());
            log.info("推送用户补充信息:身份证活体信息：" + result3);
            JSONObject resultJson3 = JSONObject.parseObject(result3);
            if(!resultJson3.containsKey("code") || !resultJson3.containsKey("data") || resultJson3.getInteger("code") != 200){
                throw new BizException("推送用户补充信息:身份证活体信息解析失败" + result3);
            }
            String imgFace = OSSUtil.uploadImage(Base64Util.decode(result3.getBytes()));
            user.setImgFace(imgFace);
            flag = true;
        }catch (Exception e){
            log.error("推送用户补充信息:用户不存在",e);
        }
        return flag;
    }

    /**
     * 获取运营商数据
     * @param orderNo
     * @param user
     * @return
     */
    public boolean checkPhone(String orderNo,User user) throws BizException {
        boolean flag = false;
        try {
            JSONObject jsonObject1=new JSONObject();
            jsonObject1.put("order_no",orderNo);
            jsonObject1.put("type","1");
            String mxMobile = RongZeRequestUtil.doPost(Constant.rongZeQueryUrl, "api.charge.data", jsonObject1.toJSONString());
            log.info("推送用户补充信息:下载运营商数据信息：" + mxMobile);
            //判断运营商数据
            JSONObject jsonObject = JSONObject.parseObject(mxMobile);
            if(!jsonObject.containsKey("code") || !jsonObject.containsKey("data") ||jsonObject.getInteger("code") != 200){
                throw new BizException("推送用户补充信息:下载运营商数据解析失败");
            }
            String dataStr = jsonObject.getJSONObject("data").toJSONString();
            //上传
            String mxMobilePath = OSSUtil.uploadStr(dataStr,RequestThread.getUid());
            if (StringUtils.isBlank(mxMobilePath)) {
                throw new BizException("推送用户补充信息:运营商数据上传失败");
            }
            MoxieMobile moxieMobile = new MoxieMobile();
            moxieMobile.setUid(RequestThread.getUid());
            moxieMobile.setPhone(user.getUserPhone());
            moxieMobile.setRemark(mxMobilePath);//oss上文件的地址存在remark这个字段
            moxieMobileMapper.insertSelective(moxieMobile);

            flag =true;
        }catch (Exception e){
            log.error("推送用户补充信息:运营商数据出错",e);
        }

        return flag;
    }



    public static String getCompanyType(String type){
        Map<String,String> map=new HashMap<>();
        map.put("BT1","机关单位");
        map.put("BT2","国有股份");
        map.put("BT3","外资");
        map.put("BT4","合资");
        map.put("BT5","民营");
        map.put("BT6","私资");
        map.put("BT7","个体");
        return  map.get(type);
    }


    public static String getCompanyGw(String type){
        Map<String,String> map=new HashMap<>();
        map.put("1","普通员工/干部");
        map.put("2","中基层管理人员/科级");
        map.put("3","高层管理人员/处级");
        map.put("4","公司法人/股东");
        map.put("5","其他/非合同工");
        return  map.get(type);
    }

    public static String getSrly(String type){
        Map<String,String> map=new HashMap<>();
        map.put("1501","普通员工/干部");
        map.put("1502","无收入来源");
        map.put("1503","经营所得");
        map.put("1504","租金收入");
        map.put("1505","投资理财");
        map.put("1506","其他收入");
        return  map.get(type);
    }


    public static String getlxr(String type){
        Map<String,String> map=new HashMap<>();
        map.put("9","父母");
        map.put("10","子女");
        map.put("11","兄弟");
        map.put("12","姐妹");
        map.put("13","配偶");
        map.put("4","同事");
        map.put("6","亲人");
        map.put("7","朋友");
        return  map.get(type);
    }

}