package com.mod.loan.mapper;

import com.mod.loan.common.mapper.MyBaseMapper;
import com.mod.loan.model.Order;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface OrderMapper extends MyBaseMapper<Order> {

    Order findByOrderNo(String orderNo);

    Order findUserLatestOrder(Long uid);

    List<Order> getByUid(Long uid);

    Integer judgeUserTypeByUid(Long uid);

    Integer countPaySuccessByUid(Long uid);

    Order findByOrderNoAndUid(@Param("orderNo") String orderNo,@Param("uid") Long uid);
}