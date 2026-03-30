package moe.dazecake.inquisition.utils;

import moe.dazecake.inquisition.model.entity.AccountEntity;
import moe.dazecake.inquisition.model.entity.ConfigEntitySet.DailyPlanNode;
import moe.dazecake.inquisition.model.entity.ConfigEntitySet.Fight;
import moe.dazecake.inquisition.model.entity.ConfigEntitySet.LoopGroup;
import moe.dazecake.inquisition.model.entity.ConfigEntitySet.LoopGroupItem;

import java.util.ArrayList;
import java.util.List;

public final class DailyPlanUtil {

    private static final int LOOP_GROUP_REPEAT_ROUNDS = 25;

    private DailyPlanUtil() {
    }

    public static void normalizeDailyPlan(AccountEntity account) {
        if (account == null || account.getConfig() == null || account.getConfig().getDaily() == null) {
            return;
        }
        var daily = account.getConfig().getDaily();
        if (daily.getPlan() != null && !daily.getPlan().isEmpty()) {
            var normalizedPlan = new ArrayList<DailyPlanNode>();
            for (DailyPlanNode node : daily.getPlan()) {
                normalizedPlan.add(normalizePlanNode(node));
            }
            daily.setPlan(normalizedPlan);
            return;
        }
        var plan = new ArrayList<DailyPlanNode>();
        if (daily.getFight() != null && !daily.getFight().isEmpty()) {
            for (Fight fight : daily.getFight()) {
                plan.add(new DailyPlanNode("fight", normalizeFight(fight), null));
            }
        }
        daily.setPlan(plan);
    }

    public static void compileDailyPlanForDevice(AccountEntity account) {
        if (account == null || account.getConfig() == null || account.getConfig().getDaily() == null) {
            return;
        }
        var daily = account.getConfig().getDaily();
        if (daily.getPlan() == null) {
            return;
        }
        var compiled = new ArrayList<Fight>();
        for (DailyPlanNode node : daily.getPlan()) {
            appendNode(compiled, normalizePlanNode(node));
        }
        daily.setFight(compiled);
    }

    private static DailyPlanNode normalizePlanNode(DailyPlanNode node) {
        if (node != null && "loop_group".equals(node.getType())) {
            var loopGroup = node.getLoopGroup();
            var items = new ArrayList<LoopGroupItem>();
            if (loopGroup != null && loopGroup.getItems() != null) {
                for (LoopGroupItem item : loopGroup.getItems()) {
                    items.add(normalizeLoopGroupItem(item));
                }
            }
            return new DailyPlanNode("loop_group", null, new LoopGroup(loopGroup == null || loopGroup.getName() == null ? "" : loopGroup.getName(), items));
        }
        return new DailyPlanNode("fight", normalizeFight(node == null ? null : node.getFight()), null);
    }

    private static Fight normalizeFight(Fight fight) {
        return new Fight(fight == null || fight.getLevel() == null ? "" : fight.getLevel(), clamp(fight == null ? null : fight.getNum()));
    }

    private static LoopGroupItem normalizeLoopGroupItem(LoopGroupItem item) {
        return new LoopGroupItem(item == null || item.getLevel() == null ? "" : item.getLevel(), clamp(item == null ? null : item.getWeight()));
    }

    private static int clamp(Integer value) {
        if (value == null || value < 1) {
            return 1;
        }
        return Math.min(value, 99);
    }

    private static void appendNode(List<Fight> compiled, DailyPlanNode node) {
        if (node == null) {
            return;
        }
        if ("loop_group".equals(node.getType())) {
            var round = new ArrayList<Fight>();
            if (node.getLoopGroup() != null && node.getLoopGroup().getItems() != null) {
                for (LoopGroupItem item : node.getLoopGroup().getItems()) {
                    var normalized = normalizeLoopGroupItem(item);
                    if (normalized.getLevel().isBlank()) {
                        continue;
                    }
                    for (int i = 0; i < normalized.getWeight(); i++) {
                        round.add(new Fight(normalized.getLevel(), 1));
                    }
                }
            }
            for (int i = 0; i < LOOP_GROUP_REPEAT_ROUNDS; i++) {
                compiled.addAll(copyFights(round));
            }
            return;
        }
        var fight = normalizeFight(node.getFight());
        if (!fight.getLevel().isBlank()) {
            compiled.add(fight);
        }
    }

    private static List<Fight> copyFights(List<Fight> fights) {
        var copies = new ArrayList<Fight>();
        for (Fight fight : fights) {
            copies.add(new Fight(fight.getLevel(), fight.getNum()));
        }
        return copies;
    }

}
