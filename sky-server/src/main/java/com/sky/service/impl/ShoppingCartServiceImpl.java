package com.sky.service.impl;

import com.sky.context.BaseContext;
import com.sky.dto.ShoppingCartDTO;
import com.sky.entity.Dish;
import com.sky.entity.Setmeal;
import com.sky.entity.ShoppingCart;
import com.sky.mapper.DishMapper;
import com.sky.mapper.SetmealMapper;
import com.sky.mapper.ShoppingCartMapper;
import com.sky.service.ShoppingCartService;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ShoppingCartServiceImpl implements ShoppingCartService {

    @Autowired
    private ShoppingCartMapper shoppingCartMapper;

    @Autowired
    private SetmealMapper setmealMapper;

    @Autowired
    private DishMapper dishMapper;

    /**
     * 添加购物车
     * @param shoppingCartDTO
     */
    @Override
    public void add(ShoppingCartDTO shoppingCartDTO) {

        //判断当前加入到购物车的商品是否已经存在
        ShoppingCart shoppingCart = new ShoppingCart();
        BeanUtils.copyProperties(shoppingCartDTO, shoppingCart);
        Long userId = BaseContext.getCurrentId();   //获取微信用户id
        shoppingCart.setUserId(userId);     //将用户id绑定到对应购物车里

        List<ShoppingCart> shoppingCartList = shoppingCartMapper.list(shoppingCart);

        //若已经存在，只需将数量加1
        if (shoppingCartList != null && shoppingCartList.size() > 0) {
            ShoppingCart cart = shoppingCartList.get(0);
            cart.setNumber(cart.getNumber() + 1);
            shoppingCartMapper.updateNumberById(cart);
        } else {
            //若不存在，则插入购物车数据
            Long setmealId = shoppingCartDTO.getSetmealId();
            Long dishId = shoppingCartDTO.getDishId();
            if (setmealId != null) {
                //本次添加到购物车的是套餐
                Setmeal setmeal = setmealMapper.getById(setmealId);
                shoppingCart.setName(setmeal.getName());
                shoppingCart.setImage(setmeal.getImage());
                shoppingCart.setAmount(setmeal.getPrice());
            } else {
                //本次添加到购物车的是菜品
                Dish dish = dishMapper.getById(dishId);
                shoppingCart.setName(dish.getName());
                shoppingCart.setImage(dish.getImage());
                shoppingCart.setAmount(dish.getPrice());
            }

            shoppingCart.setNumber(1);
            shoppingCart.setCreateTime(LocalDateTime.now());

            shoppingCartMapper.insert(shoppingCart);
        }
    }


    /**
     * 查看购物车
     * @return
     */
    @Override
    public List<ShoppingCart> showShoppingCart() {

        //获取当前微信用户id
        Long userId = BaseContext.getCurrentId();

        ShoppingCart shoppingCart = ShoppingCart.builder()
                .userId(userId)
                .build();

        List<ShoppingCart> shoppingCartList = shoppingCartMapper.list(shoppingCart);

        return shoppingCartList;
    }


    /**
     * 清空购物车
     */
    @Override
    public void cleanShoppingCart() {

        //获取当前微信用户id
        Long userId = BaseContext.getCurrentId();

        shoppingCartMapper.deleteByUserId(userId);

    }


    /**
     * 删除购物车中一个商品
     * @param shoppingCartDTO
     */
    @Override
    public void deleteOne(ShoppingCartDTO shoppingCartDTO) {

        ShoppingCart shoppingCart = new ShoppingCart();
        BeanUtils.copyProperties(shoppingCartDTO, shoppingCart);

        //获取当前微信用户id
        Long userId = BaseContext.getCurrentId();

        shoppingCart.setUserId(userId);

        shoppingCartMapper.deleteOne(shoppingCart);

    }


}
