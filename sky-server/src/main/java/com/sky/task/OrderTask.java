package com.sky.task;

import com.sky.entity.Orders;
import com.sky.mapper.OrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单任务类，定时处理订单状态
 */
@Component
@Slf4j
public class OrderTask {

    @Autowired
    private OrderMapper orderMapper;

    /**
     * 处理未付款超时订单 (超时15min)，自动取消订单
     */
    @Scheduled(cron = "0 * * * * ?")    //每分钟触发一次
    public void processTimeoutOrder() {

        log.info("定时处理超时订单: {}", LocalDateTime.now());

        //找到处理超时订单
        LocalDateTime time = LocalDateTime.now().plusMinutes(-15);
        List<Orders> ordersList = orderMapper.getByStatusAndOrderTimeLT(Orders.PENDING_PAYMENT, time);

        //修改超时订单信息
        if (ordersList != null && ordersList.isEmpty()) {
            for (Orders orders : ordersList) {
                orders.setStatus(Orders.CANCELLED);
                orders.setCancelTime(LocalDateTime.now());
                orders.setCancelReason("订单超时，自动取消");

                orderMapper.update(orders);
            }
        }

    }

    /**
     * 处理派送中长时间未完成订单，统一自动完成订单
     */
    @Scheduled(cron = "0 0 1 * * ?")   //每天凌晨1点触发
    public void processDeliveryOrder() {

        log.info("定时处理处于派送中的订单: {}", LocalDateTime.now());

        //找到每天凌晨0点前下单的订单
        LocalDateTime time = LocalDateTime.now().plusMinutes(-60);
        List<Orders> ordersList = orderMapper.getByStatusAndOrderTimeLT(Orders.DELIVERY_IN_PROGRESS, time);

        //修改派送中订单信息
        if (ordersList != null && ordersList.isEmpty()) {
            for (Orders orders : ordersList) {
                orders.setStatus(Orders.COMPLETED);
                orderMapper.update(orders);
            }
        }

    }

}
