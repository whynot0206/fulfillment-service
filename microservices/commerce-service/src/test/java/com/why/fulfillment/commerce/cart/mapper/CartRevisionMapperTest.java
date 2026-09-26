package com.why.fulfillment.commerce.cart.mapper;

import com.why.fulfillment.commerce.cart.service.CartItemSnapshot;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Actual MyBatis dynamic-SQL binding checks, without a database connection. */
class CartRevisionMapperTest {
    @Test
    void conditionalDeleteBindsUserRowSkuAndRevisionForEveryOriginalSnapshot() throws Exception {
        String script = String.join(" ", CartItemMapper.class
                .getMethod("deleteUnchangedSnapshots", Long.class, List.class).getAnnotation(Delete.class).value());
        BoundSql bound = new XMLLanguageDriver().createSqlSource(new Configuration(), script, Map.class)
                .getBoundSql(Map.of("userId", 9L, "snapshots", List.of(
                        new CartItemSnapshot(1001L, 2, true, 101L, 7L),
                        new CartItemSnapshot(1002L, 1, true, 102L, 3L))));

        String sql = bound.getSql().replaceAll("\\s+", " ");
        assertTrue(sql.contains("where user_id = ? and"));
        assertEquals(2, sql.split("id = \\? and sku_id = \\? and revision = \\?", -1).length - 1);
        assertEquals(7, bound.getParameterMappings().size());
        assertEquals(101L, bound.getAdditionalParameter("__frch_snapshot_0.rowId"));
        assertEquals(7L, bound.getAdditionalParameter("__frch_snapshot_0.revision"));
        assertEquals(102L, bound.getAdditionalParameter("__frch_snapshot_1.rowId"));
        assertEquals(3L, bound.getAdditionalParameter("__frch_snapshot_1.revision"));
    }

    @Test
    void allSameRowMutationsAdvanceRevisionEvenWhenVisibleValuesReturnToOriginal() throws Exception {
        String add = String.join(" ", CartItemMapper.class
                .getMethod("upsertAccumulate", Long.class, Long.class, Integer.class, Integer.class)
                .getAnnotation(Insert.class).value());
        String quantity = String.join(" ", CartItemMapper.class
                .getMethod("updateQuantity", Long.class, Long.class, Integer.class).getAnnotation(Update.class).value());
        String selection = String.join(" ", CartItemMapper.class
                .getMethod("updateSelected", Long.class, Long.class, boolean.class).getAnnotation(Update.class).value());
        for (String sql : List.of(add, quantity, selection)) {
            assertTrue(sql.contains("revision = revision + 1"));
        }
    }

    @Test
    void cartReadsIncludeRevisionAndRowIdentity() throws Exception {
        for (String name : List.of("selectByUserId", "selectByUserAndSku")) {
            Class<?>[] parameters = name.equals("selectByUserId")
                    ? new Class<?>[]{Long.class} : new Class<?>[]{Long.class, Long.class};
            String sql = String.join(" ", CartItemMapper.class.getMethod(name, parameters)
                    .getAnnotation(Select.class).value());
            assertTrue(sql.contains("select id,"));
            assertTrue(sql.contains("selected, revision,"));
        }
    }
}
