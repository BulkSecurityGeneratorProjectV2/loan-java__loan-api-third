package com.mod.loan.controller.rongze;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.mod.loan.common.enums.ResponseEnum;
import com.mod.loan.common.enums.UserOriginEnum;
import com.mod.loan.common.exception.BizException;
import com.mod.loan.common.model.RequestThread;
import com.mod.loan.common.model.ResponseBean;
import com.mod.loan.config.Constant;
import com.mod.loan.config.redis.RedisMapper;
import com.mod.loan.controller.bank.BankRequestHandler;
import com.mod.loan.controller.order.RepayRequestHandler;
import com.mod.loan.mapper.OrderUserMapper;
import com.mod.loan.model.Merchant;
import com.mod.loan.model.User;
import com.mod.loan.service.MerchantService;
import com.mod.loan.service.UserService;
import com.mod.loan.service.impl.rongze.*;
import com.mod.loan.util.HttpUtils;
import com.mod.loan.util.rongze.BizDataUtil;
import com.mod.loan.util.rongze.SignUtil;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.sql.DataSource;
import java.util.Properties;

/**
 * @ author liujianjian
 * @ date 2019/5/15 18:01
 */
@Slf4j
@RestController
@RequestMapping("/rongze")
public class RongZeRequestController implements InitializingBean {


    @Resource
    private RedisMapper redisMapper;
    @Resource
    private UserService userService;
    @Resource
    private RongZeRequestHandler rongZeRequestHandler;
    @Resource
    private CertRequestHandler certRequestHandler;
    @Resource
    private UserInfoBaseRequestHandler userInfoBaseRequestHandler;
    @Resource
    private UserInfoAdditRequestHandler userInfoAdditRequestHandler;
    @Resource
    private AuditResultRequestHandler auditResultRequestHandler;
    @Resource
    private WithDrawRequestHandler withDrawRequestHandler;
    @Resource
    private BankRequestHandler bankRequestHandler;
    @Resource
    private RepayRequestHandler repayRequestHandler;

    @Autowired
    private MerchantService merchantService;
    @Resource
    private OrderUserMapper orderUserMapper;

    private static String logPre = "融泽入口请求, ";

    @RequestMapping("/dispatcherRequest")
    public Object dispatcherRequest(HttpServletRequest request, @RequestBody JSONObject param) {

        long s = System.currentTimeMillis();

        log.warn(logPre + "=============================================" + param.toJSONString());

        Object result;
        String method = param.getString("method");
        log.info(logPre + "收到, method: " + method);

        try {//校验 sig
            String sign = param.getString("sign");
            boolean check = SignUtil.checkSign(param.toJSONString(), sign);
            if (!check) throw new BizException(ResponseEnum.M4006);

            //解密 bizData
            if ("1".equals(param.getString("biz_enc"))) {
                String bizDataStr = param.getString("biz_data");
                String bizData = BizDataUtil.decryptBizData(bizDataStr, param.getString("des_key"));
                param.put("biz_data", bizData);
                log.warn("========================" + method + "解密后的数据：" + param.toJSONString());
            }

            //绑定线程变量
            this.binRequestThread(request, param, method);

            if (StringUtils.isBlank(method)) throw new BizException(ResponseEnum.M5000);

            switch (method) {
                case "fund.withdraw.req": //提交用户确认收款信息
                    result = rongZeRequestHandler.handleOrderSubmit(param);
                    break;
                case "fund.deal.contract": //查询借款合同
                    result = rongZeRequestHandler.handleQueryContract(param);
                    break;
                case "fund.order.status": //查询订单状态
                    result = rongZeRequestHandler.handleQueryOrderStatus(param);
                    break;
                case "fund.payment.req": //用户还款
                    result = rongZeRequestHandler.handleRepayment(param);
                    break;
                case "fund.bank.bind": //用户验证银行卡
                    result = bankRequestHandler.bankCardCode(param);
                    break;
                case "fund.bank.verify": //用户绑定银行卡
                    result = bankRequestHandler.bankBind(param);
                    break;
                case "fund.payment.plan": //查询还款计划
                    result = repayRequestHandler.getRepayPlan(param);
                    break;
                case "fund.payment.result": //查询还款状态
                    result = repayRequestHandler.getRepayStatus(param);
                    break;

                case "fund.cert.auth": //查询复贷黑名单信息
                    result = certRequestHandler.certAuth(param);
                    break;
                case "fund.userinfo.base": //提交用户基本信息
                    result = userInfoBaseRequestHandler.userInfoBase(param);
                    break;
                case "fund.userinfo.addit": //查询用户补充信息
                    result = userInfoAdditRequestHandler.userInfoAddit(param);
                    break;
                case "fund.audit.result": //查询审批结论
                    result = auditResultRequestHandler.auditResult(param);
                    break;
                case "fund.withdraw.trial": //试算接口
                    result = withDrawRequestHandler.withdrawTria(param);
                    break;
                // TODO: 2019/5/15 其它 method
                default:
                    throw new BizException(ResponseEnum.M5000.getCode(), "method not found");
            }
        } catch (Exception e) {
            logFail(e, "【" + method + "】方法出错：" + param.toJSONString());
            result = e instanceof BizException ? ResponseBean.fail(((BizException) e)) : ResponseBean.fail(e.getMessage());
        }

        log.info(logPre + "结束返回, result: " + JSON.toJSONString(result) + ", method: " + method + ", costTime: " + (System.currentTimeMillis() - s) + " ms");
        return result;
    }

    private void binRequestThread(HttpServletRequest request, JSONObject param, String method) throws BizException {
        RequestThread.remove();// 移除本地线程变量

        JSONObject bizData = JSONObject.parseObject(param.getString("biz_data"));

        Long uid = null;
        String orderNo = null;
        switch (method) {
            case "fund.userinfo.base": //用户基本信息
                orderNo = bizData.containsKey("orderInfo") ? bizData.getJSONObject("orderInfo").getString("order_no") : null;
                break;
            default:
                orderNo = bizData.containsKey("order_no") ? bizData.getString("order_no") : null;
        }
        log.info("订单编号:" + orderNo);
        if (StringUtils.isEmpty(orderNo)) {
            throw new BizException("订单编号不存在");
        }
        String key = orderNo + UserOriginEnum.RZ.getCode();
        try {
            //同一个用户锁6秒
            if (redisMapper.lock(key, 6000)) {
                if (redisMapper.hasKey(key)) {
                    uid = Long.parseLong(redisMapper.get(key));
                } else {
                    uid = orderUserMapper.getUidByOrderNoAndSource(orderNo, Integer.parseInt(UserOriginEnum.RZ.getCode()));
                    redisMapper.set(key, uid);
                }
                String sourceId = param.getString("source_id"); //标志用户来源的app
                String clientAlias = Constant.merchant;
                String sign = param.getString("sign");
                String token = param.getString("token");
                RequestThread.setClientAlias(clientAlias);
                RequestThread.setIp(HttpUtils.getIpAddr(request, "."));
                RequestThread.setRequestTime(System.currentTimeMillis());
                RequestThread.setToken(token);
                RequestThread.setSign(sign);
                RequestThread.setSourceId(sourceId);
                RequestThread.setUid(uid);
                //判断商户是否配置好
                Merchant merchant = merchantService.findMerchantByAlias(clientAlias);
                if (merchant == null) {
                    log.info("商户【" + RequestThread.getClientAlias() + "】不存在，未配置");
                    throw new BizException("商户不存在");
                }
            }
        } catch (Exception e) {
            log.error("binRequestThread异常错误", e);
            throw new BizException(e.getMessage());
        } finally {
            redisMapper.unlock(key);
        }

    }

    private void logFail(Exception e, String info) {
        if (e instanceof BizException)
            log.info(getPreLog() + e.getMessage() + "||相关数据：" + info);
        else
            log.error("融泽入口请求系统异常, " + getPreLog() + e.getMessage() + "||相关数据：" + info, e);
    }

    private String getPreLog() {
        Long uid = RequestThread.getUid();
        if (uid == null) return "";

        String pre = "userId: %s, username: %s, phone: %s, ", username = "", phone = "";
        User user = userService.selectByPrimaryKey(uid);
        if (user != null) {
            username = user.getUserName();
            phone = user.getUserPhone();
        }
        return String.format(pre, uid, username, phone);
    }

    @Resource
    private DataSource dataSource;

    @Override
    public void afterPropertiesSet() throws Exception {
        try {
            String s = dataSource.toString();
            HikariDataSource ds;
            int max = -1;
            long connectionTimeout = -1;
            if (s.contains("HikariDataSource")) {
                ds = (HikariDataSource) dataSource;
                max = ds.getMaximumPoolSize();
                connectionTimeout = ds.getConnectionTimeout();
            }
            log.info("当前数据源: " + dataSource + ", 最大连接数: " + max + ", connectionTimeout: " + connectionTimeout + " ms");
        } catch (Throwable e) {

        }
    }
}
