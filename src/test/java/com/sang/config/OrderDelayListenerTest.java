package com.sang.config;

import com.rabbitmq.client.Channel;
import com.sang.dto.OrderDelayMessage;
import com.sang.entity.VoucherOrder;
import com.sang.entity.SeckillVoucher;
import com.sang.service.ISeckillVoucherService;
import com.sang.service.IVoucherOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static com.sang.utils.RedisConstants.SECKILL_STOCK_KEY;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * OrderDelayListener 单元测试 — 订单超时取消
 *
 * <p>验证 15 分钟延迟消息触发后的各种场景：正常取消、已支付跳过、订单不存在、异常处理。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("订单超时取消监听器测试")
class OrderDelayListenerTest {

    @Mock private IVoucherOrderService voucherOrderService;
    @Mock private ISeckillVoucherService seckillVoucherService;
    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private SetOperations<String, String> setOps;
    @Mock private Channel channel;
    @Mock private com.baomidou.mybatisplus.extension.conditions.update.UpdateChainWrapper<SeckillVoucher> updateChainWrapper;

    @InjectMocks
    private OrderDelayListener listener;

    @BeforeEach
    void setUp() {
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(stringRedisTemplate.opsForSet()).thenReturn(setOps);
    }

    private void mockStockRollbackChain() {
        when(seckillVoucherService.update()).thenReturn(updateChainWrapper);
        when(updateChainWrapper.setSql(anyString())).thenReturn(updateChainWrapper);
        when(updateChainWrapper.eq(anyString(), any())).thenReturn(updateChainWrapper);
        when(updateChainWrapper.update()).thenReturn(true);
    }

    // ==================== 正常超时取消 ====================

    @Nested
    @DisplayName("订单超时取消 — 正常流程")
    class NormalCancellationTests {

        @Test
        @DisplayName("订单状态=1(未支付) → 三重回滚 → ACK")
        void shouldCancelAndRollbackWhenOrderIsUnpaid() throws Exception {
            // given
            OrderDelayMessage msg = new OrderDelayMessage(1L, 1001L, 10L);
            VoucherOrder order = new VoucherOrder();
            order.setId(1L);
            order.setUserId(1001L);
            order.setVoucherId(10L);
            order.setStatus(1); // 未支付

            when(voucherOrderService.getById(1L)).thenReturn(order);
            when(voucherOrderService.updateById(any(VoucherOrder.class))).thenReturn(true);
            mockStockRollbackChain();

            when(valueOps.increment(SECKILL_STOCK_KEY + "10")).thenReturn(6L);
            when(setOps.remove("seckill:order" + "10", "1001")).thenReturn(1L);

            // when
            listener.onOrderDelayTimeout(msg, channel, 100L);

            // then: 验证三重回滚
            // ① 订单状态 → 4(已取消)
            ArgumentCaptor<VoucherOrder> orderCaptor =
                    ArgumentCaptor.forClass(VoucherOrder.class);
            verify(voucherOrderService).updateById(orderCaptor.capture());
            assertEquals(4, orderCaptor.getValue().getStatus(), "订单状态应为 4(已取消)");

            // ② DB 库存回滚
            verify(seckillVoucherService).update();
            verify(updateChainWrapper).setSql("stock = stock + 1");
            verify(updateChainWrapper).eq("voucher_id", 10L);
            verify(updateChainWrapper).update();

            // ②bis Redis 库存回滚
            verify(valueOps).increment(SECKILL_STOCK_KEY + "10");

            // ③ Redis Set 移除
            verify(setOps).remove("seckill:order" + "10", "1001");

            // ACK
            verify(channel).basicAck(100L, false);
        }
    }

    // ==================== 跳过取消 ====================

    @Nested
    @DisplayName("订单超时取消 — 跳过场景")
    class SkipCancellationTests {

        @Test
        @DisplayName("订单不存在 → ACK 跳过")
        void shouldAckWhenOrderNotFound() throws Exception {
            // given
            OrderDelayMessage msg = new OrderDelayMessage(999L, 1001L, 10L);
            when(voucherOrderService.getById(999L)).thenReturn(null);

            // when
            listener.onOrderDelayTimeout(msg, channel, 100L);

            // then
            verify(channel).basicAck(100L, false);
            verify(voucherOrderService, never()).updateById(any());
            verify(seckillVoucherService, never()).update();
            verify(valueOps, never()).increment(anyString());
            verify(setOps, never()).remove(anyString(), anyString());
        }

        @Test
        @DisplayName("订单已支付(status=2) → ACK 跳过")
        void shouldAckWhenOrderAlreadyPaid() throws Exception {
            // given
            OrderDelayMessage msg = new OrderDelayMessage(1L, 1001L, 10L);
            VoucherOrder order = new VoucherOrder();
            order.setId(1L);
            order.setStatus(2); // 已支付

            when(voucherOrderService.getById(1L)).thenReturn(order);

            // when
            listener.onOrderDelayTimeout(msg, channel, 100L);

            // then
            verify(channel).basicAck(100L, false);
            verify(voucherOrderService, never()).updateById(any());
            verify(seckillVoucherService, never()).update();
            verify(valueOps, never()).increment(anyString());
            verify(setOps, never()).remove(anyString(), anyString());
        }

        @Test
        @DisplayName("订单已取消(status=4) → ACK 跳过")
        void shouldAckWhenOrderAlreadyCancelled() throws Exception {
            // given
            OrderDelayMessage msg = new OrderDelayMessage(1L, 1001L, 10L);
            VoucherOrder order = new VoucherOrder();
            order.setId(1L);
            order.setStatus(4); // 已取消

            when(voucherOrderService.getById(1L)).thenReturn(order);

            // when
            listener.onOrderDelayTimeout(msg, channel, 100L);

            // then
            verify(channel).basicAck(100L, false);
            verify(voucherOrderService, never()).updateById(any());
            verify(seckillVoucherService, never()).update();
            verify(valueOps, never()).increment(anyString());
            verify(setOps, never()).remove(anyString(), anyString());
        }

        @Test
        @DisplayName("订单已退款(status=6) → ACK 跳过")
        void shouldAckWhenOrderAlreadyRefunded() throws Exception {
            // given
            OrderDelayMessage msg = new OrderDelayMessage(1L, 1001L, 10L);
            VoucherOrder order = new VoucherOrder();
            order.setId(1L);
            order.setStatus(6); // 已退款

            when(voucherOrderService.getById(1L)).thenReturn(order);

            // when
            listener.onOrderDelayTimeout(msg, channel, 100L);

            // then
            verify(channel).basicAck(100L, false);
            verify(voucherOrderService, never()).updateById(any());
            verify(seckillVoucherService, never()).update();
            verify(valueOps, never()).increment(anyString());
            verify(setOps, never()).remove(anyString(), anyString());
        }
    }

    // ==================== 异常处理 ====================

    @Nested
    @DisplayName("订单超时取消 — 异常处理")
    class ExceptionHandlingTests {

        @Test
        @DisplayName("查询订单时抛异常 → basicReject(不重入队)")
        void shouldRejectWhenExceptionOccurs() throws Exception {
            // given
            OrderDelayMessage msg = new OrderDelayMessage(1L, 1001L, 10L);
            when(voucherOrderService.getById(1L))
                    .thenThrow(new RuntimeException("DB down"));

            // when
            listener.onOrderDelayTimeout(msg, channel, 100L);

            // then: 拒绝且不重入队（防止死循环）
            verify(channel).basicReject(100L, false);
        }

        @Test
        @DisplayName("回滚库存时抛异常 → basicReject → 不 ACK")
        void shouldRejectWhenStockRollbackFails() throws Exception {
            // given
            OrderDelayMessage msg = new OrderDelayMessage(1L, 1001L, 10L);
            VoucherOrder order = new VoucherOrder();
            order.setId(1L);
            order.setStatus(1); // 未支付

            when(voucherOrderService.getById(1L)).thenReturn(order);
            when(voucherOrderService.updateById(any(VoucherOrder.class)))
                    .thenReturn(true);
            when(seckillVoucherService.update())
                    .thenThrow(new RuntimeException("DB deadlock"));

            // when
            listener.onOrderDelayTimeout(msg, channel, 100L);

            // then
            verify(channel).basicReject(100L, false);
            verify(channel, never()).basicAck(anyLong(), anyBoolean());
            verify(valueOps, never()).increment(anyString());
            verify(setOps, never()).remove(anyString(), anyString());
        }

        @Test
        @DisplayName("basicReject 自身抛异常 → 不向上传播")
        void shouldNotPropagateWhenRejectItselfFails() throws Exception {
            // given
            OrderDelayMessage msg = new OrderDelayMessage(1L, 1001L, 10L);
            when(voucherOrderService.getById(1L))
                    .thenThrow(new RuntimeException("DB down"));
            doThrow(new RuntimeException("Channel closed"))
                    .when(channel).basicReject(anyLong(), anyBoolean());

            // when & then: 不应向上层抛异常
            assertDoesNotThrow(() -> listener.onOrderDelayTimeout(msg, channel, 100L));
        }
    }

}
