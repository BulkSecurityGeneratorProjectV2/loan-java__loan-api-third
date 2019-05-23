package com.mod.loan.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.mod.loan.common.enums.ResponseEnum;
import com.mod.loan.common.message.OrderRepayQueryMessage;
import com.mod.loan.common.model.RequestThread;
import com.mod.loan.common.model.ResultMessage;
import com.mod.loan.config.Constant;
import com.mod.loan.config.rabbitmq.RabbitConst;
import com.mod.loan.controller.check.Log;
import com.mod.loan.model.Order;
import com.mod.loan.model.OrderRepay;
import com.mod.loan.model.User;
import com.mod.loan.model.UserBank;
import com.mod.loan.service.*;
import com.mod.loan.util.StringUtil;
import com.mod.loan.util.TimeUtils;
import com.mod.loan.util.baofoo.rsa.RsaCodingUtil;
import com.mod.loan.util.baofoo.rsa.SignatureUtils;
import com.mod.loan.util.baofoo.util.FormatUtil;
import com.mod.loan.util.baofoo.util.HttpUtil;
import com.mod.loan.util.baofoo.util.SecurityUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * @ author liujianjian
 * @ date 2019/5/17 15:54
 */
@Slf4j
@Service
public class BaofooServiceImpl implements BaofooService {

    @Resource
    private UserService userService;
    @Resource
    private RabbitTemplate rabbitTemplate;
    @Resource
    private UserBankService userBankService;
    @Resource
    private OrderRepayService orderRepayService;

    @Override
    public ResultMessage repay(Order order) {
        //报文发送日期时间
        String sendTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        //商户私钥
        String pfxpath = Constant.baoFooKeyStorePath;
        //宝付公钥
        String cerpath = Constant.baoFooPubKeyPath;

        long uid = order.getUid();

        UserBank userBank = userBankService.selectUserCurrentBankCard(uid);
        if(userBank == null) {
            log.info("用户: "+uid+", 未绑定银行卡信息, userBank null");
            return new ResultMessage(ResponseEnum.M3003);
        }

        User user = userService.selectByPrimaryKey(uid);

        // 支付流水号
        String orderSeriesId = StringUtil.getOrderNumber("bf");
        try {
            //商户自定义(可随机生成  AES key长度为=16位)
            String aesKey = "4f66405c4f77405c";
            //使用接收方的公钥加密后的对称密钥，并做Base64转码，明文01|对称密钥，01代表AES[密码商户自定义]
            String dgtlEnvlp = "01|" + aesKey;
            //公钥加密
            dgtlEnvlp = RsaCodingUtil.encryptByPubCerFile(SecurityUtil.Base64Encode(dgtlEnvlp), cerpath);
            //签约协议号（确认支付返回）
            String protocolNo = userBank.getForeignId();
            //先BASE64后进行AES加密
            protocolNo = SecurityUtil.AesEncrypt(SecurityUtil.Base64Encode(protocolNo), aesKey);
            //信用卡：信用卡有效期|安全码,借记卡：传空
            String cardInfo = "";
            //结果回调
            String returnUrl = "";

            Map<String, String> dateArray = new TreeMap<>();
            dateArray.put("send_time", sendTime);
            //报文流水号
            dateArray.put("msg_id", "TISN" + System.currentTimeMillis());
            dateArray.put("version", Constant.baoFooVersion);
            dateArray.put("terminal_id", Constant.baoFooTerminalId);
            //交易类型(参看：文档中《交易类型枚举》)
            dateArray.put("txn_type", "08");
            dateArray.put("member_id", Constant.baoFooMemberId);

            dateArray.put("trans_id", orderSeriesId);
            dateArray.put("dgtl_envlp", dgtlEnvlp);
            //用户在商户平台唯一ID (和绑卡时要一致)
            dateArray.put("user_id", uid + "");
            //签约协议号（密文）
            dateArray.put("protocol_no", protocolNo);
            //交易金额 [单位：分  例：1元则提交100]，此处注意数据类型的转转，建议使用BigDecimal类弄进行转换为字串
            //以分为单位，取整
            long amount;
            if ("dev".equals(Constant.ENVIROMENT)) {
                amount = 1L;
            } else {
                amount = new BigDecimal(100).multiply(order.getShouldRepay()).longValue();
            }
            dateArray.put("txn_amt", amount + "");
            //卡信息
            dateArray.put("card_info", cardInfo);

            Map<String, String> riskItem = new HashMap<>();
            //--------风控基础参数-------------
            // 说明风控参数必须，按商户开通行业、真实交易信息传，不可传固定值。

            //行业类目 详见附录《行业类目》
            riskItem.put("goodsCategory", "02");
            //用户在商户系统中的登陆名（手机号、邮箱等标识）
            riskItem.put("userLoginId", user.getUserPhone());
            riskItem.put("userEmail", "");
            //用户手机号
            riskItem.put("userMobile", user.getUserPhone());
            //用户在商户系统中注册使用的名字
            riskItem.put("registerUserName", user.getUserName());
            //用户在平台是否已实名，1：是 ；0：不是
            riskItem.put("identifyState", "1");
            //用户身份证号
            riskItem.put("userIdNo", user.getUserCertNo());
            //格式为：YYYYMMDDHHMMSS
            riskItem.put("registerTime", TimeUtils.parseTime(user.getCreateTime(), TimeUtils.dateformat5));
            //用户在商户端注册时留存的IP
            riskItem.put("registerIp", RequestThread.getIp());
            //持卡人姓名
            riskItem.put("chName", user.getUserName());
            //持卡人身份证号
            riskItem.put("chIdNo", user.getUserCertNo());
            //持卡人银行卡号
            riskItem.put("chCardNo", userBank.getCardNo());
            //持卡人手机
            riskItem.put("chMobile", userBank.getCardPhone());
            //持卡人支付IP
            riskItem.put("chPayIp", RequestThread.getIp());
            //加载设备指纹中的订单号
            riskItem.put("deviceOrderNo", "");

            //放入风控参数
            dateArray.put("risk_item", JSONObject.toJSONString(riskItem));

            //最多填写三个地址,不同地址用间使用‘|’分隔
            dateArray.put("return_url", returnUrl);
            String signVStr = FormatUtil.coverMap2String(dateArray);
            //签名
            String signature = SecurityUtil.sha1X16(signVStr, "UTF-8");
            String sign = SignatureUtils.encryptByRSA(signature, pfxpath, Constant.baoFooKeyStorePassword);
            //签名域
            dateArray.put("signature", sign);

            String postString = HttpUtil.RequestForm(Constant.baoFooRepayUrl, dateArray);
            log.info("请求宝付协议支付返回接口: {}", postString);

            Map<String, String> returnData = FormatUtil.getParm(postString);

            if (!returnData.containsKey("signature")) {
                //缺少验签参数！
                log.error("请求宝付协议支付，返回结果缺少验签参数！，还款订单号={}, rseponse", orderSeriesId, postString);
                return new ResultMessage(ResponseEnum.M4000.getCode(), "还款异常");
            }
            String rSign = returnData.get("signature");
            //需要删除签名字段
            returnData.remove("signature");
            String rSignVStr = FormatUtil.coverMap2String(returnData);
            //签名
            String rSignature = SecurityUtil.sha1X16(rSignVStr, "UTF-8");

            if (!SignatureUtils.verifySignature(cerpath, rSignature, rSign)) {
                //验签成功
                log.error("请求宝付协议支付，返回结果缺少验签参数！，还款订单号={}, rseponse", orderSeriesId, postString);
                return new ResultMessage(ResponseEnum.M4000.getCode(), "还款异常");
            }
            if (!returnData.containsKey("resp_code")) {
                //缺少resp_code参数！
                log.error("请求宝付协议支付，返回结果缺少resp_code参数，还款订单号={}, rseponse", orderSeriesId, postString);
                return new ResultMessage(ResponseEnum.M4000.getCode(), "还款异常");
            }
            if ("S".equals(returnData.get("resp_code"))
                    || "I".equals(returnData.get("resp_code"))
                    || "F".equals(returnData.get("resp_code"))) {
                Log.write("协议支付请求成功");
                OrderRepay orderRepay = new OrderRepay();
                orderRepay.setRepayNo(orderSeriesId);
                orderRepay.setUid(uid);
                orderRepay.setOrderId(order.getId());
                orderRepay.setRepayType(1);
                BigDecimal repayMoney = new BigDecimal(amount).divide(new BigDecimal(100));
                orderRepay.setRepayMoney(repayMoney);
                orderRepay.setBank(userBank.getCardName());
                orderRepay.setBankNo(userBank.getCardNo());
                orderRepay.setCreateTime(new Date());
                orderRepay.setUpdateTime(new Date());
                orderRepay.setRepayStatus(0);
                orderRepayService.insertSelective(orderRepay);

                OrderRepayQueryMessage message = new OrderRepayQueryMessage();
                message.setMerchantAlias(RequestThread.getClientAlias());
                message.setRepayNo(orderSeriesId);
                message.setTimes(1);
                message.setRepayType(1);
                rabbitTemplate.convertAndSend(RabbitConst.baofoo_queue_repay_order_query, message);

                JSONObject object = new JSONObject();
                object.put("repayOrderNo", orderSeriesId);
                object.put("shouldRepayAmount", order.getShouldRepay().toPlainString());
                return new ResultMessage(ResponseEnum.M2000, object);
            } else {
                //异常不得做为订单状态。
                log.error("用户主动还款，调用宝付协议支付异常，还款订单号={}, rseponse", orderSeriesId, postString);
                return new ResultMessage(ResponseEnum.M4000.getCode(), "还款异常");
            }

        } catch (Exception e) {
            return new ResultMessage(ResponseEnum.M4000.getCode(), "还款异常");
        }
    }
}
