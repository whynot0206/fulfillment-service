package com.why.fulfillment.commerce.checkout.mapper;

import com.why.fulfillment.commerce.checkout.entity.CheckoutRequest;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** Immutable checkout intent plus database-clock leases; no remote call holds a DB transaction. */
@Mapper
public interface CheckoutRequestMapper {
    String COLUMNS = "id,user_id,idempotency_key,request_digest,total_amount,order_id,status,last_error,"
            + "create_time,update_time,request_payload,recovery_deadline,recovery_state,lease_owner,"
            + "lease_until,attempt_count,next_retry_time,result_state,cart_cleanup_required";

    /** The id, payload and initial lease become durable in the SAME unique-key-protected INSERT. */
    @Insert("""
            insert into checkout_request
              (user_id,idempotency_key,request_digest,total_amount,order_id,status,request_payload,
               recovery_deadline,recovery_state,lease_owner,lease_until,attempt_count,next_retry_time)
            values (#{userId},#{idempotencyKey},#{requestDigest},#{totalAmount},#{orderId},0,#{requestPayload},
               timestampadd(second,#{recoveryWindowSeconds},current_timestamp),0,#{leaseOwner},
               timestampadd(second,#{leaseSeconds},current_timestamp),1,current_timestamp)
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertClaim(CheckoutRequest claim);

    @Select("select " + COLUMNS + " from checkout_request where user_id=#{userId} and idempotency_key=#{idempotencyKey}")
    CheckoutRequest selectByUserAndKey(@Param("userId") long userId, @Param("idempotencyKey") String key);

    @Select("select " + COLUMNS + " from checkout_request where id=#{id}")
    CheckoutRequest selectById(@Param("id") long id);

    @Select("select " + COLUMNS + " from checkout_request where id=#{id} and status=0"
            + " and lease_owner=#{owner} and lease_until>current_timestamp")
    CheckoutRequest selectOwned(@Param("id") long id, @Param("owner") String owner);

    @Select("""
            select id from checkout_request where status=0 and recovery_state=0
              and (next_retry_time is null or next_retry_time<=current_timestamp)
              and (lease_until is null or lease_until<=current_timestamp)
            order by id limit #{limit}
            """)
    List<Long> findReadyIds(@Param("limit") int limit);

    @Update("""
            update checkout_request set lease_owner=#{owner},
              lease_until=timestampadd(second,#{leaseSeconds},current_timestamp),attempt_count=attempt_count+1
            where id=#{id} and status=0 and recovery_state=#{recoveryState}
              and (next_retry_time is null or next_retry_time<=current_timestamp)
              and (lease_until is null or lease_until<=current_timestamp)
            """)
    int tryClaim(@Param("id") long id, @Param("owner") String owner,
                 @Param("leaseSeconds") long seconds, @Param("recoveryState") int recoveryState);

    @Select("""
            select count(*) from checkout_request where id=#{id} and status=0 and recovery_state=0
              and lease_owner=#{owner} and lease_until>current_timestamp and recovery_deadline>current_timestamp
            """)
    int creationAllowed(@Param("id") long id, @Param("owner") String owner);

    @Update("""
            update checkout_request set status=1,result_state=#{state},last_error=null,
              cart_cleanup_required=#{cleanup},lease_owner=null,lease_until=null,next_retry_time=null
            where id=#{id} and status=0 and lease_owner=#{owner} and lease_until>current_timestamp
            """)
    int finishSubmitted(@Param("id") long id, @Param("owner") String owner,
                        @Param("state") String state, @Param("cleanup") boolean cleanup);

    @Update("""
            update checkout_request set cart_cleanup_required=0
            where id=#{id} and status=1 and cart_cleanup_required=1
            """)
    int markCartCleaned(@Param("id") long id);

    @Update("""
            update checkout_request set status=2,result_state=#{state},last_error=#{error},
              lease_owner=null,lease_until=null,next_retry_time=null
            where id=#{id} and status=0 and lease_owner=#{owner} and lease_until>current_timestamp
            """)
    int finishRejected(@Param("id") long id, @Param("owner") String owner,
                       @Param("state") String state, @Param("error") String error);

    @Update("""
            update checkout_request set last_error=#{error},recovery_state=#{recoveryState},
              next_retry_time=timestampadd(second,#{delay},current_timestamp),lease_owner=null,lease_until=null
            where id=#{id} and status=0 and lease_owner=#{owner} and lease_until>current_timestamp
            """)
    int deferOwned(@Param("id") long id, @Param("owner") String owner, @Param("error") String error,
                   @Param("recoveryState") int state, @Param("delay") long delay);
}
