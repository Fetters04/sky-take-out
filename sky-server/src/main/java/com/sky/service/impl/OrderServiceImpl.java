package com.sky.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.constant.MessageConstant;
import com.sky.context.BaseContext;
import com.sky.dto.*;
import com.sky.entity.*;
import com.sky.exception.AddressBookBusinessException;
import com.sky.exception.OrderBusinessException;
import com.sky.exception.ShoppingCartBusinessException;
import com.sky.mapper.*;
import com.sky.result.PageResult;
import com.sky.service.OrderService;
import com.sky.utils.WeChatPayUtil;
import com.sky.vo.OrderPaymentVO;
import com.sky.vo.OrderStatisticsVO;
import com.sky.vo.OrderSubmitVO;
import com.sky.vo.OrderVO;
import com.sky.websocket.WebSocketServer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class OrderServiceImpl implements OrderService {

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderDetailMapper orderDetailMapper;

    @Autowired
    private AddressBookMapper addressBookMapper;

    @Autowired
    private ShoppingCartMapper shoppingCartMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private WeChatPayUtil weChatPayUtil;

    @Autowired
    private WebSocketServer webSocketServer;

    /**
     * 用户下单
     * @param ordersSubmitDTO
     * @return
     */
    @Override
    @Transactional
    public OrderSubmitVO submitOrder(OrdersSubmitDTO ordersSubmitDTO) {

        //1.处理业务异常（地址簿为空，购物车为空）
        AddressBook addressBook = addressBookMapper.getById(ordersSubmitDTO.getAddressBookId());
        if (addressBook == null) {
            throw new AddressBookBusinessException(MessageConstant.ADDRESS_BOOK_IS_NULL);
        }

        Long userId = BaseContext.getCurrentId();   //获取当前微信用户id

        ShoppingCart shoppingCart = new ShoppingCart();
        shoppingCart.setUserId(userId);
        List<ShoppingCart> shoppingCartList = shoppingCartMapper.list(shoppingCart);
        if (shoppingCartList == null || shoppingCartList.isEmpty()) {
            throw new ShoppingCartBusinessException(MessageConstant.SHOPPING_CART_IS_NULL);
        }

        //2.向订单表插入1条数据
        Orders orders = new Orders();
        BeanUtils.copyProperties(ordersSubmitDTO, orders);
        orders.setNumber(String.valueOf(System.currentTimeMillis()));
        orders.setStatus(Orders.PENDING_PAYMENT);
        orders.setPayStatus(Orders.UN_PAID);
        orders.setUserId(userId);
        orders.setAddressBookId(addressBook.getId());
        orders.setOrderTime(LocalDateTime.now());
        orders.setPhone(addressBook.getPhone());
        orders.setConsignee(addressBook.getConsignee());

        String address = addressBook.getProvinceName() + addressBook.getCityName()
                + addressBook.getDistrictName() + addressBook.getDetail();
        orders.setAddress(address);

        orderMapper.insert(orders);

        //3.向订单明细表插入n条数据
        List<OrderDetail> orderDetailList = new ArrayList<>();
        for (ShoppingCart cart : shoppingCartList) {
            OrderDetail orderDetail = new OrderDetail();
            BeanUtils.copyProperties(cart, orderDetail);
            orderDetail.setOrderId(orders.getId()); //设置当前订单明细关联的订单id
            orderDetailList.add(orderDetail);
        }
        orderDetailMapper.insertBatch(orderDetailList);

        //4.清空当前用户的购物车数据
        shoppingCartMapper.deleteByUserId(userId);

        //5.封装VO返回
        OrderSubmitVO orderSubmitVO = OrderSubmitVO.builder()
                .id(orders.getId())
                .orderNumber(orders.getNumber())
                .orderAmount(orders.getAmount())
                .orderTime(orders.getOrderTime())
                .build();

        return orderSubmitVO;
    }

    /**
     * 订单支付
     *
     * @param ordersPaymentDTO
     * @return
     */
    @Override
    public OrderPaymentVO payment(OrdersPaymentDTO ordersPaymentDTO) throws Exception {
        // 当前登录用户id
        Long userId = BaseContext.getCurrentId();
        User user = userMapper.getById(userId);

        // //调用微信支付接口，生成预支付交易单
        // JSONObject jsonObject = weChatPayUtil.pay(
        //         ordersPaymentDTO.getOrderNumber(), //商户订单号
        //         new BigDecimal(0.01), //支付金额，单位 元
        //         "苍穹外卖订单", //商品描述
        //         user.getOpenid() //微信用户的openid
        // );
        //
        // if (jsonObject.getString("code") != null && jsonObject.getString("code").equals("ORDERPAID")) {
        //     throw new OrderBusinessException("该订单已支付");
        // }

        JSONObject jsonObject = new JSONObject();
        jsonObject.put("code", "ORDERPAID");

        OrderPaymentVO vo = jsonObject.toJavaObject(OrderPaymentVO.class);
        vo.setPackageStr(jsonObject.getString("package"));

        // 根据订单号查询当前用户的该订单
        Orders ordersDB = orderMapper.getByNumberAndUserId(ordersPaymentDTO.getOrderNumber(), userId);

        // 更新订单的状态、支付方式、支付状态、结账时间
        Orders orders = Orders.builder()
               .id(ordersDB.getId())
               .status(Orders.TO_BE_CONFIRMED) // 订单状态，待接单
               .payStatus(Orders.PAID) // 支付状态，已支付
               .checkoutTime(LocalDateTime.now()) // 更新支付时间
               .build();
        orderMapper.update(orders);

        paySuccess(ordersPaymentDTO.getOrderNumber());

        return vo;
    }

    /**
     * 支付成功，修改订单状态
     *
     * @param outTradeNo
     */
    public void paySuccess(String outTradeNo) {

        // 根据订单号查询订单
        Orders ordersDB = orderMapper.getByNumber(outTradeNo);

        // 根据订单id更新订单的状态、支付方式、支付状态、结账时间
        Orders orders = Orders.builder()
                .id(ordersDB.getId())
                .status(Orders.TO_BE_CONFIRMED)
                .payStatus(Orders.PAID)
                .checkoutTime(LocalDateTime.now())
                .build();

        orderMapper.update(orders);


        //通过websocket向客户端浏览器推送消息 json: type orderId content
        Map map = new HashMap();
        map.put("type", 1);     //1-来单提醒 2-客户催单
        map.put("orderId", ordersDB.getId());
        map.put("content", "订单号：" + outTradeNo);

        String json = JSON.toJSONString(map);
        webSocketServer.sendToAllClient(json);
    }

    /**
     * 分页查询订单
     * @param ordersPageQueryDTO
     * @return
     */
    @Override
    public PageResult pageQuery(OrdersPageQueryDTO ordersPageQueryDTO) {

        //只展示微信用户自己的订单数据
        Long userId = BaseContext.getCurrentId();
        ordersPageQueryDTO.setUserId(userId);

        //开启分页
        int pageNum = ordersPageQueryDTO.getPage();
        int pageSize = ordersPageQueryDTO.getPageSize();
        PageHelper.startPage(pageNum, pageSize);

        //分页条件查询
        Page<Orders> pageOrders = orderMapper.pageQuery(ordersPageQueryDTO);

        //构造要OrderVO对象集合
        List<OrderVO> orderVOList = new ArrayList<>();

        List<Orders> ordersList = pageOrders.getResult();

        //封装集合中的OrderVO对象
        for (Orders orders : ordersList) {
            OrderVO orderVO = new OrderVO();
            BeanUtils.copyProperties(orders, orderVO);

            Long orderId = orders.getId();
            List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(orderId);
            orderVO.setOrderDetailList(orderDetailList);

            //加入到集合中
            orderVOList.add(orderVO);
        }

        long total = pageOrders.getTotal();

        return new PageResult(total, orderVOList);
    }

    /**
     * 查询订单详情
     * @param id
     * @return
     */
    @Override
    public OrderVO findOrderDetail(Long id) {

        Orders orders = orderMapper.getById(id);

        List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(orders.getId());

        //封装VO对象
        OrderVO orderVO = new OrderVO();
        BeanUtils.copyProperties(orders, orderVO);
        orderVO.setOrderDetailList(orderDetailList);

        return orderVO;
    }

    /**
     * 根据id取消订单
     * @param id
     */
    @Override
    public void cancelById(Long id) throws Exception {

        Orders orders = orderMapper.getById(id);

        //判断订单是否存在
        if (orders == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }

        /*
           待支付和待接单状态下，用户才可直接取消订单(修改状态和对应取消信息)
        */

        //订单状态 1待付款 2待接单 3已接单 4派送中 5已完成 6已取消
        Integer status = orders.getStatus();

        if (status > 2) {
            //状态错误，不可取消订单
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        //若处于待接单状态下，需要进行退款
        if (status.equals(Orders.TO_BE_CONFIRMED)) {
            // //调用微信支付退款接口
            // weChatPayUtil.refund(
            //         orders.getNumber(), //商户订单号
            //         orders.getNumber(), //商户退款单号
            //         new BigDecimal(0.01),//退款金额，单位 元
            //         new BigDecimal(0.01));//原订单金额

            //支付状态修改为 退款
            orders.setPayStatus(Orders.REFUND);
        }

        //更改状态，设置取消原因和取消时间
        orders.setStatus(Orders.CANCELLED);
        orders.setCancelReason("用户取消");
        orders.setCancelTime(LocalDateTime.now());
        orderMapper.update(orders);

    }

    /**
     * 再来一单
     * @param id
     */
    @Override
    public void repetition(Long id) {

        Long userId = BaseContext.getCurrentId();

        //获取订单对应的订单明细数据
        List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(id);

        //将订单中的商品重新加入到购物车中
        List<ShoppingCart> shoppingCartList = new ArrayList<>();
        for (OrderDetail orderDetail : orderDetailList) {
            ShoppingCart shoppingCart = new ShoppingCart();
            BeanUtils.copyProperties(orderDetail, shoppingCart, "id");

            shoppingCart.setUserId(userId);
            shoppingCart.setCreateTime(LocalDateTime.now());

            shoppingCartList.add(shoppingCart);
        }

        //批量插入购物车数据
        shoppingCartMapper.insertBatch(shoppingCartList);

    }

    /**
     * 订单搜索
     * @param ordersPageQueryDTO
     * @return
     */
    @Override
    public PageResult conditionSearch(OrdersPageQueryDTO ordersPageQueryDTO) {

        //开启分页
        int pageNum = ordersPageQueryDTO.getPage();
        int pageSize = ordersPageQueryDTO.getPageSize();
        PageHelper.startPage(pageNum, pageSize);

        Page<Orders> page = orderMapper.pageQuery(ordersPageQueryDTO);

        //封装OrderVO集合
        List<OrderVO> orderVOList = getOrderVOList(page);

        return new PageResult(page.getTotal(), orderVOList);
    }


    //封装OrderVO方法
    private List<OrderVO> getOrderVOList(Page<Orders> page) {

        List<OrderVO> orderVOList = new ArrayList<>();

        List<Orders> ordersList = page.getResult();

        if (!CollectionUtils.isEmpty(ordersList)) {
            for (Orders orders : page) {
                OrderVO orderVO = new OrderVO();
                BeanUtils.copyProperties(orders, orderVO);

                //获取菜品信息orderDishes，封装到OrderVO中
                String orderDishes = getOrderDishesStr(orders);
                orderVO.setOrderDishes(orderDishes);

                orderVOList.add(orderVO);
            }
        }

        return orderVOList;
    }


    /**
     * 根据订单id获取菜品信息字符串
     *
     * @param orders
     * @return
     */
    private String getOrderDishesStr(Orders orders) {
        // 查询订单菜品详情信息（订单中的菜品和数量）
        List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(orders.getId());

        // 将每一条订单菜品信息拼接为字符串（格式：宫保鸡丁*3；）
        List<String> orderDishList = orderDetailList.stream().map(x -> {
            String orderDish = x.getName() + "*" + x.getNumber() + ";";
            return orderDish;
        }).collect(Collectors.toList());

        // 将该订单对应的所有菜品信息拼接在一起
        return String.join("", orderDishList);
    }

    /**
     * 各个状态的订单数量统计
     * @return
     */
    @Override
    public OrderStatisticsVO statistics() {

        //封装VO对象
        OrderStatisticsVO orderStatisticsVO = new OrderStatisticsVO();

        Integer confirmed = orderMapper.countByStatus(Orders.CONFIRMED);  //待派送数量
        Integer deliveryInProgress = orderMapper.countByStatus(Orders.DELIVERY_IN_PROGRESS);   //派送中数量
        Integer toBeConfirmed = orderMapper.countByStatus(Orders.TO_BE_CONFIRMED);  //待接单数量

        orderStatisticsVO.setConfirmed(confirmed);
        orderStatisticsVO.setDeliveryInProgress(deliveryInProgress);
        orderStatisticsVO.setToBeConfirmed(toBeConfirmed);

        return orderStatisticsVO;
    }


    /**
     * 接单
     * @param ordersConfirmDTO
     */
    @Override
    public void confirm(OrdersConfirmDTO ordersConfirmDTO) {

        Orders orders = Orders.builder()
                        .id(ordersConfirmDTO.getId())
                                .status(Orders.CONFIRMED)
                                        .build();

        orderMapper.update(orders);

    }

    /**
     * 拒单
     * @param ordersRejectionDTO
     */
    @Override
    public void rejection(OrdersRejectionDTO ordersRejectionDTO) throws Exception {

        // 根据id查询订单
        Orders ordersDB = orderMapper.getById(ordersRejectionDTO.getId());

        // 订单只有存在且状态为2（待接单）才可以拒单
        if (ordersDB == null || !ordersDB.getStatus().equals(Orders.TO_BE_CONFIRMED)) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        //支付状态
        Integer payStatus = ordersDB.getPayStatus();
        if (payStatus == Orders.PAID) {
            //用户已支付，需要退款
            // String refund = weChatPayUtil.refund(
            //         ordersDB.getNumber(),
            //         ordersDB.getNumber(),
            //         new BigDecimal(0.01),
            //         new BigDecimal(0.01));
            //
            // log.info("申请退款：{}", refund);

            //支付状态修改为退款
            ordersDB.setPayStatus(Orders.REFUND);
        }

        // 拒单需要退款，根据订单id更新订单状态、拒单原因、取消时间
        Orders orders = new Orders();
        orders.setId(ordersDB.getId());
        orders.setStatus(Orders.CANCELLED);
        orders.setRejectionReason(ordersRejectionDTO.getRejectionReason());
        orders.setCancelTime(LocalDateTime.now());

        orderMapper.update(orders);

    }

    /**
     * 取消订单
     * @param ordersCancelDTO
     */
    @Override
    public void cancel(OrdersCancelDTO ordersCancelDTO) throws Exception {

        // 根据id查询订单
        Orders ordersDB = orderMapper.getById(ordersCancelDTO.getId());

        //订单状态只有未完成（不为5）才能取消
        if (ordersDB == null || ordersDB.getStatus().equals(Orders.COMPLETED)) {
            throw new OrderBusinessException(MessageConstant.ORDER_COMPLETED);
        }

        //支付状态
        Integer payStatus = ordersDB.getPayStatus();
        if (payStatus == Orders.PAID) {
            // //用户已支付并且订单未完成, 需要退款
            // String refund = weChatPayUtil.refund(
            //         ordersDB.getNumber(),
            //         ordersDB.getNumber(),
            //         new BigDecimal(0.01),
            //         new BigDecimal(0.01));
            //
            // log.info("申请退款：{}", refund);

            //支付状态修改为退款
            ordersDB.setPayStatus(Orders.REFUND);
        }

        // 管理端取消订单需要退款，根据订单id更新订单状态、取消原因、取消时间
        Orders orders = new Orders();
        orders.setId(ordersCancelDTO.getId());
        orders.setStatus(Orders.CANCELLED);
        orders.setCancelReason(ordersCancelDTO.getCancelReason());
        orders.setCancelTime(LocalDateTime.now());
        orderMapper.update(orders);

    }

    /**
     * 派送订单
     * @param id
     */
    @Override
    public void delivery(Long id) {

        // 根据id查询订单
        Orders ordersDB = orderMapper.getById(id);

        // 订单只有存在且状态为3（已接单）才可以派送
        if (ordersDB == null || !ordersDB.getStatus().equals(Orders.CONFIRMED)) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        // 更新订单状态,状态转为派送中
        Orders orders = Orders.builder()
                .id(id)
                .status(Orders.DELIVERY_IN_PROGRESS)
                .build();

        orderMapper.update(orders);

    }

    /**
     * 完成订单
     * @param id
     */
    @Override
    public void complete(Long id) {

        // 根据id查询订单
        Orders ordersDB = orderMapper.getById(id);

        // 订单只有存在且状态为4（派送中）才可以完成订单
        if (ordersDB == null || !ordersDB.getStatus().equals(Orders.DELIVERY_IN_PROGRESS)) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        // 更新订单状态,状态转为已完成
        Orders orders = Orders.builder()
                .id(id)
                .status(Orders.COMPLETED)
                .deliveryTime(LocalDateTime.now())
                .build();

        orderMapper.update(orders);

    }

    /**
     * 催单
     * @param id
     */
    @Override
    public void reminder(Long id) {

        //查询订单
        Orders ordersDB = orderMapper.getById(id);

        //校验订单是否存在
        if (ordersDB == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }

        //向管理端推送催单提醒
        Map map = new HashMap();
        map.put("type", 2);     //1-来单提醒 2-客户催单
        map.put("orderId", id);
        map.put("content", "订单号：" + ordersDB.getNumber());

        String json = JSON.toJSONString(map);
        webSocketServer.sendToAllClient(json);

    }

}
