package tw.exilecore.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.MapCodec;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.DustColorTransitionOptions;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.PowerParticleOption;
import net.minecraft.core.particles.SculkChargeParticleOptions;
import net.minecraft.core.particles.ShriekParticleOption;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.particles.SpellParticleOption;
import net.minecraft.core.particles.TrailParticleOption;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import tw.exilecore.client.core.FxCodec;
import tw.exilecore.client.core.FxLook;
import tw.exilecore.client.core.FxZones;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 畫伺服器送來的特效（exilecore:fx）。
 * <ul>
 *   <li>一次性的（點、一團、一圈、線、光束）：用原版的粒子引擎在本地生粒子，跟伺服器送粒子的算法一樣（常態分布的 offset、速度），
 *       所以看起來跟以前一樣；一般的照玩家自己的「粒子」設定減量，重要的（預警）跟以前一樣強制顯示。</li>
 *   <li>持續範圍（START／UPDATE／STOP）：記在 {@link FxZones}；RING／AREA／LINE 在地上畫一圈細線（照地形高低）、AREA／LINE 裡面半透明，
 *       顏色照粒子；重要的往紅色偏、一秒閃一次。另外每 0.5 秒撒少量粒子（照「粒子」設定，最少 = 不撒）。</li>
 * </ul>
 * 全部在遊戲主執行緒（收封包、tick、畫）。
 */
final class FxRenderer {

    /** 一次性的特效一筆最多生幾顆（防亂送）。 */
    static final int MAX_PER_EFFECT = 1024;
    /** 多遠以內的範圍才畫（格，水平）。 */
    static final double DRAW_RANGE = 128;
    /** 多遠以內的範圍才撒粒子。 */
    static final double AMBIENT_RANGE = 64;
    /** 地面高度多久重新量一次（tick；方塊可能變了）。 */
    static final int GROUND_REFRESH = 40;
    /** 地上的圖形離地多高（避免跟方塊表面打架）。 */
    static final float FILL_LIFT = 0.02f;
    static final float EDGE_LIFT = 0.035f;

    static final FxZones ZONES = new FxZones();
    private static long ticks;
    /** 範圍是在哪個世界收到的（換世界就清掉）。 */
    private static ClientLevel level;
    private static final Map<FxCodec.Style, Optional<ParticleOptions>> OPTIONS = new HashMap<>();

    private FxRenderer() {
    }

    static void clear() {
        ZONES.clear();
        OPTIONS.clear();
    }

    /**
     * 換世界（進地圖、回城、斷線）了就全部清掉。收封包與 tick 都先叫這個：換世界後的第一包特效可能比第一個 tick 早到，
     * 不能讓 tick 再把新世界剛收到的範圍清掉。
     */
    static void syncLevel(final ClientLevel current) {
        if (current != level) {
            level = current;
            clear();
        }
    }

    // ---- 收封包 ----

    static void onPacket(final byte[] data) {
        final Minecraft mc = Minecraft.getInstance();
        final ClientLevel level = mc.level;
        syncLevel(level);
        if (level == null) {
            return;
        }
        final List<FxCodec.Number> numbers = new ArrayList<>();
        final List<FxCodec.Effect> effects = FxCodec.decode(data, numbers); // 2026-11-02（1.4）：傷害數字也在這包
        if (effects == null) {
            ExileCoreClient.LOG.debug("星骸特效封包格式不對（{} 位元組），略過", data == null ? 0 : data.length);
            return;
        }
        if (!numbers.isEmpty()) {
            DamageNumberRenderer.accept(numbers);
        }
        for (final FxCodec.Effect effect : effects) {
            if (effect.persistent()) {
                ZONES.apply(effect, ticks);
            } else {
                try {
                    spawnOnce(level, effect);
                } catch (final RuntimeException e) {
                    ExileCoreClient.LOG.debug("畫不了特效 {}：{}", effect.style(), e.toString());
                }
            }
        }
    }

    // ---- 一次性的 ----

    private static void spawnOnce(final ClientLevel level, final FxCodec.Effect e) {
        final ParticleOptions options = options(e.style());
        if (options == null) {
            return;
        }
        final RandomSource random = level.getRandom();
        final boolean force = e.important();
        final int count = Math.min(MAX_PER_EFFECT, e.count());
        switch (e.shape()) {
            case FxCodec.SHAPE_RING, FxCodec.SHAPE_AREA -> {
                final int points = Math.max(1, count);
                final double start = random.nextDouble() * Math.PI * 2;
                for (int i = 0; i < points; i++) {
                    final double angle = start + Math.PI * 2 * i / points;
                    vanilla(level, options, force, e.x() + Math.cos(angle) * e.radius(), e.y(), e.z() + Math.sin(angle) * e.radius(), 1, 0, 0, 0,
                            e.extra(), random);
                }
            }
            case FxCodec.SHAPE_LINE, FxCodec.SHAPE_BEAM -> {
                final double length = FxLook.length(e);
                final int points = count > 0 ? count : Math.min(MAX_PER_EFFECT, FxLook.lineSegments(length) * (e.shape() == FxCodec.SHAPE_BEAM ? 2 : 1));
                for (int i = 0; i < points; i++) {
                    final double t = points == 1 ? 0.5 : (double) i / (points - 1);
                    vanilla(level, options, force, Mth.lerp(t, e.x(), e.ex()), Mth.lerp(t, e.y(), e.ey()), Mth.lerp(t, e.z(), e.ez()), 1, e.dx(),
                            e.dy(), e.dz(), e.extra(), random);
                }
            }
            default -> vanilla(level, options, force, e.x(), e.y(), e.z(), count, e.dx(), e.dy(), e.dz(), e.extra(), random);
        }
    }

    /** 跟原版收到粒子封包時一樣：count 0 = 一顆有方向的（offset 當方向、extra 當速度）；不然 count 顆，offset、速度是常態分布。 */
    private static void vanilla(final ClientLevel level, final ParticleOptions options, final boolean force, final double x, final double y,
                                final double z, final int count, final double dx, final double dy, final double dz, final double extra,
                                final RandomSource random) {
        if (count == 0) {
            level.addParticle(options, force, false, x, y, z, dx * extra, dy * extra, dz * extra);
            return;
        }
        for (int i = 0; i < count; i++) {
            final double ox = random.nextGaussian() * dx;
            final double oy = random.nextGaussian() * dy;
            final double oz = random.nextGaussian() * dz;
            final double vx = random.nextGaussian() * extra;
            final double vy = random.nextGaussian() * extra;
            final double vz = random.nextGaussian() * extra;
            level.addParticle(options, force, false, x + ox, y + oy, z + oz, vx, vy, vz);
        }
    }

    // ---- 粒子的樣式 ----

    /** 樣式 → 原版的粒子（不認得、資料對不上的 null；結果記著）。 */
    static ParticleOptions options(final FxCodec.Style style) {
        if (style == null) {
            return null;
        }
        final Optional<ParticleOptions> cached = OPTIONS.get(style);
        if (cached != null) {
            return cached.orElse(null);
        }
        ParticleOptions built;
        try {
            built = build(style);
        } catch (final RuntimeException e) {
            built = null;
        }
        if (built == null) {
            ExileCoreClient.LOG.debug("不認得的特效粒子 {}", style);
        }
        if (OPTIONS.size() > 1024) {
            OPTIONS.clear();
        }
        OPTIONS.put(style, Optional.ofNullable(built));
        return built;
    }

    @SuppressWarnings("unchecked")
    private static <T extends ParticleOptions> ParticleType<T> cast(final ParticleType<?> type) {
        return (ParticleType<T>) type;
    }

    private static ParticleOptions build(final FxCodec.Style s) {
        final Identifier id = Identifier.tryParse(s.particle());
        if (id == null) {
            return null;
        }
        final ParticleType<?> type = BuiltInRegistries.PARTICLE_TYPE.getOptional(id).orElse(null);
        if (type == null) {
            return null;
        }
        final float size = Math.max(0.01f, Math.min(4f, s.f()));
        final ParticleOptions options = switch (s.kind()) {
            case FxCodec.DATA_NONE -> type instanceof SimpleParticleType simple ? simple : null;
            case FxCodec.DATA_DUST -> type == ParticleTypes.DUST ? new DustParticleOptions(s.a() & 0xFFFFFF, size) : null;
            case FxCodec.DATA_DUST_TRANSITION -> type == ParticleTypes.DUST_COLOR_TRANSITION
                    ? new DustColorTransitionOptions(s.a() & 0xFFFFFF, s.b() & 0xFFFFFF, size) : null;
            case FxCodec.DATA_BLOCK -> {
                final BlockState state = blockState(s.text());
                yield state == null || state.isAir() ? null : new BlockParticleOption(FxRenderer.<BlockParticleOption>cast(type), state);
            }
            case FxCodec.DATA_ITEM -> {
                final Identifier itemId = Identifier.tryParse(s.text() == null ? "" : s.text());
                final Item item = itemId == null ? null : BuiltInRegistries.ITEM.getOptional(itemId).orElse(null);
                yield item == null || item == Items.AIR ? null : new ItemParticleOption(FxRenderer.<ItemParticleOption>cast(type), item);
            }
            case FxCodec.DATA_COLOR -> ColorParticleOption.create(FxRenderer.<ColorParticleOption>cast(type), s.a());
            case FxCodec.DATA_SPELL -> SpellParticleOption.create(FxRenderer.<SpellParticleOption>cast(type), 0xFF000000 | s.a(), s.f());
            case FxCodec.DATA_FLOAT -> type == ParticleTypes.SCULK_CHARGE ? new SculkChargeParticleOptions(s.f())
                    : PowerParticleOption.create(FxRenderer.<PowerParticleOption>cast(type), s.f());
            case FxCodec.DATA_INT -> type == ParticleTypes.SHRIEK ? new ShriekParticleOption(Math.max(0, s.n())) : null;
            case FxCodec.DATA_TRAIL -> type == ParticleTypes.TRAIL
                    ? new TrailParticleOption(new Vec3(s.tx(), s.ty(), s.tz()), 0xFF000000 | s.a(), Math.max(1, s.n())) : null;
            default -> null;
        };
        return options != null && matches(type, options) ? options : null;
    }

    /** 這個粒子種類真的吃這種資料（用它自己的編碼器試一次；對不上會在畫的時候讓遊戲當掉，所以先擋掉）。 */
    @SuppressWarnings("unchecked")
    private static boolean matches(final ParticleType<?> type, final ParticleOptions options) {
        if (options.getType() != type) {
            return false;
        }
        try {
            ((MapCodec<ParticleOptions>) type.codec()).codec().encodeStart(NbtOps.INSTANCE, options);
            return true;
        } catch (final ClassCastException e) {
            return false;
        }
    }

    private static BlockState blockState(final String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, text, false).blockState();
        } catch (final CommandSyntaxException | RuntimeException e) {
            final int bracket = text.indexOf('[');
            final Identifier id = Identifier.tryParse(bracket < 0 ? text : text.substring(0, bracket));
            final Block block = id == null ? null : BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
            return block == null ? null : block.defaultBlockState();
        }
    }

    // ---- 每 tick：到期、撒粒子 ----

    static void tick(final Minecraft mc) {
        ticks++;
        final ClientLevel level = mc.level;
        syncLevel(level);
        ZONES.tick(ticks);
        if (level == null || ZONES.size() == 0 || mc.getCameraEntity() == null) {
            return;
        }
        final int setting = particleSetting(mc);
        if (setting >= FxLook.PARTICLES_MINIMAL) {
            return; // 「粒子：最少」：只畫地上的圈
        }
        final Vec3 eye = mc.getCameraEntity().position();
        final RandomSource random = level.getRandom();
        for (final FxZones.Zone zone : ZONES.zones()) {
            if (Math.floorMod(ticks + zone.id(), FxLook.AMBIENT_INTERVAL) != 0) {
                continue; // 每個範圍錯開，不會同一個 tick 一起撒
            }
            final FxCodec.Effect e = zone.effect();
            if (horizontal(eye, e) > AMBIENT_RANGE + e.radius()) {
                continue;
            }
            final ParticleOptions options = options(e.style());
            if (options != null) {
                try {
                    ambient(level, options, e, FxLook.ambientCount(e, setting), random);
                } catch (final RuntimeException ex) {
                    ExileCoreClient.LOG.debug("畫不了範圍 {}：{}", e.style(), ex.toString());
                }
            }
        }
    }

    private static int particleSetting(final Minecraft mc) {
        final ParticleStatus status = mc.options.particles().get();
        return status == ParticleStatus.MINIMAL ? FxLook.PARTICLES_MINIMAL
                : status == ParticleStatus.DECREASED ? FxLook.PARTICLES_DECREASED : FxLook.PARTICLES_ALL;
    }

    private static double horizontal(final Vec3 eye, final FxCodec.Effect e) {
        double x = e.x();
        double z = e.z();
        if (FxCodec.hasEnd(e.shape())) {
            x = (e.x() + e.ex()) / 2; // 線：量到中間
            z = (e.z() + e.ez()) / 2;
        }
        final double dx = eye.x - x;
        final double dz = eye.z - z;
        return Math.sqrt(dx * dx + dz * dz) - (FxCodec.hasEnd(e.shape()) ? FxLook.length(e) / 2 : 0);
    }

    /** 持續範圍撒少量粒子（地上已經畫了圈，比伺服器的少）。 */
    private static void ambient(final ClientLevel level, final ParticleOptions options, final FxCodec.Effect e, final int n,
                                final RandomSource random) {
        final boolean force = e.important();
        switch (e.shape()) {
            case FxCodec.SHAPE_RING -> {
                for (int i = 0; i < n; i++) {
                    final double angle = random.nextDouble() * Math.PI * 2;
                    level.addParticle(options, force, false, e.x() + Math.cos(angle) * e.radius(), e.y() + 0.05,
                            e.z() + Math.sin(angle) * e.radius(), 0, 0, 0);
                }
            }
            case FxCodec.SHAPE_AREA -> {
                final int edge = Math.max(1, n / 3);
                for (int i = 0; i < n; i++) {
                    final double angle = random.nextDouble() * Math.PI * 2;
                    final double dist = i < edge ? e.radius() : e.radius() * Math.sqrt(random.nextDouble());
                    final double height = i < edge ? 0.05 : 0.05 + random.nextDouble() * Math.max(0, e.dy());
                    final double speed = i < edge ? 0 : e.extra();
                    level.addParticle(options, force, false, e.x() + Math.cos(angle) * dist, e.y() + height, e.z() + Math.sin(angle) * dist,
                            random.nextGaussian() * speed, random.nextGaussian() * speed, random.nextGaussian() * speed);
                }
            }
            case FxCodec.SHAPE_LINE, FxCodec.SHAPE_BEAM -> {
                final double hx = e.ez() - e.z();
                final double hz = -(e.ex() - e.x());
                final double h = Math.sqrt(hx * hx + hz * hz);
                final double width = e.shape() == FxCodec.SHAPE_LINE ? Math.max(0.2, e.radius()) : e.radius() * 0.5;
                for (int i = 0; i < n; i++) {
                    final double t = random.nextDouble();
                    final double side = h < 1e-6 ? 0 : (random.nextDouble() * 2 - 1) * width / h;
                    level.addParticle(options, force, false, Mth.lerp(t, e.x(), e.ex()) + hx * side,
                            Mth.lerp(t, e.y(), e.ey()) + (e.shape() == FxCodec.SHAPE_LINE ? 0.1 : 0), Mth.lerp(t, e.z(), e.ez()) + hz * side,
                            0, 0, 0);
                }
            }
            default -> {
                final double spread = Math.max(0.1, e.radius() * 0.5);
                vanilla(level, options, force, e.x(), e.y(), e.z(), n, Math.max(e.dx(), spread), Math.max(e.dy(), 0.1), Math.max(e.dz(), spread),
                        e.extra(), random);
            }
        }
    }

    // ---- 每幀：地上的圈、圓盤、線 ----

    /** 地上的圖形（相對 anchor 的座標；高度已經照地形量好）。不會再改，畫的時候直接用。 */
    private record Ground(double ax, double ay, double az, float[] fill, List<Edge> edges, FxCodec.Style style, boolean important,
                          long measured) {
    }

    /** 一條外框：點（x,y,z）＋每點的水平法線（nx,nz），帶寬在畫的時候算（重要的會閃）。 */
    private record Edge(float[] points, float[] normals, boolean closed) {
    }

    static void render(final LevelRenderContext context) {
        if (ZONES.size() == 0) {
            return;
        }
        final Minecraft mc = Minecraft.getInstance();
        final ClientLevel level = mc.level;
        if (level == null || context.levelState() == null || context.levelState().cameraRenderState == null) {
            return;
        }
        final Vec3 camera = context.levelState().cameraRenderState.pos;
        if (camera == null) {
            return;
        }
        final List<Ground> shapes = new ArrayList<>();
        for (final FxZones.Zone zone : ZONES.zones()) {
            final FxCodec.Effect e = zone.effect();
            if (!FxLook.drawsGround(e.shape()) || horizontal(camera, e) > DRAW_RANGE + e.radius()) {
                continue;
            }
            Ground ground = zone.cache instanceof Ground cached ? cached : null;
            if (ground == null || ticks - ground.measured() >= GROUND_REFRESH || ticks < ground.measured()) {
                try {
                    ground = measure(level, e);
                } catch (final RuntimeException ex) {
                    ground = null;
                }
                zone.cache = ground;
            }
            if (ground != null) {
                shapes.add(ground);
            }
        }
        if (shapes.isEmpty()) {
            return;
        }
        final double t = System.nanoTime() / 1.0e9 * 20; // 閃爍用（tick）
        final PoseStack poseStack = context.poseStack();
        context.submitNodeCollector().submitCustomGeometry(poseStack, RenderTypes.debugQuads(), (pose, consumer) -> {
            for (final Ground ground : shapes) {
                emit(pose, consumer, ground, camera, t);
            }
        });
    }

    private static void emit(final PoseStack.Pose pose, final VertexConsumer consumer, final Ground g, final Vec3 camera, final double t) {
        final float ox = (float) (g.ax() - camera.x);
        final float oy = (float) (g.ay() - camera.y);
        final float oz = (float) (g.az() - camera.z);
        if (g.fill() != null) {
            final int color = FxLook.fillColor(g.style(), g.important(), t);
            final float[] f = g.fill();
            for (int i = 0; i + 2 < f.length; i += 3) {
                consumer.addVertex(pose, ox + f[i], oy + f[i + 1] + FILL_LIFT, oz + f[i + 2]).setColor(color);
            }
        }
        final int color = FxLook.edgeColor(g.style(), g.important(), t);
        final float half = FxLook.edgeWidth(g.important(), t) / 2;
        for (final Edge edge : g.edges()) {
            final float[] p = edge.points();
            final float[] n = edge.normals();
            final int count = p.length / 3;
            final int segments = edge.closed() ? count : count - 1;
            for (int s = 0; s < segments; s++) {
                final int a = s;
                final int b = (s + 1) % count;
                final float ay = oy + p[a * 3 + 1] + EDGE_LIFT;
                final float by = oy + p[b * 3 + 1] + EDGE_LIFT;
                consumer.addVertex(pose, ox + p[a * 3] - n[a * 2] * half, ay, oz + p[a * 3 + 2] - n[a * 2 + 1] * half).setColor(color);
                consumer.addVertex(pose, ox + p[a * 3] + n[a * 2] * half, ay, oz + p[a * 3 + 2] + n[a * 2 + 1] * half).setColor(color);
                consumer.addVertex(pose, ox + p[b * 3] + n[b * 2] * half, by, oz + p[b * 3 + 2] + n[b * 2 + 1] * half).setColor(color);
                consumer.addVertex(pose, ox + p[b * 3] - n[b * 2] * half, by, oz + p[b * 3 + 2] - n[b * 2 + 1] * half).setColor(color);
            }
        }
    }

    /** 量地面高度、算出地上的圖形。 */
    private static Ground measure(final ClientLevel level, final FxCodec.Effect e) {
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        final double ax = e.x();
        final double ay = e.y();
        final double az = e.z();
        final List<Edge> edges = new ArrayList<>();
        float[] fill = null;
        if (e.shape() == FxCodec.SHAPE_LINE) {
            final double lx = e.ex() - e.x();
            final double lz = e.ez() - e.z();
            final double length = Math.sqrt(lx * lx + lz * lz);
            if (length < 0.05) {
                return null;
            }
            final float dirX = (float) (lx / length);
            final float dirZ = (float) (lz / length);
            final float nx = -dirZ;
            final float nz = dirX;
            final double width = Math.max(0.3, e.radius());
            final int m = FxLook.lineSegments(length);
            final float[] left = new float[(m + 1) * 3];
            final float[] right = new float[(m + 1) * 3];
            final float[] normals = new float[(m + 1) * 2];
            for (int j = 0; j <= m; j++) {
                final double t = (double) j / m;
                final double cx = e.x() + lx * t;
                final double cz = e.z() + lz * t;
                final double hint = Mth.lerp(t, e.y(), e.ey());
                final double lxw = cx + nx * width;
                final double lzw = cz + nz * width;
                final double rxw = cx - nx * width;
                final double rzw = cz - nz * width;
                left[j * 3] = (float) (lxw - ax);
                left[j * 3 + 1] = (float) (ground(level, pos, lxw, hint, lzw) - ay);
                left[j * 3 + 2] = (float) (lzw - az);
                right[j * 3] = (float) (rxw - ax);
                right[j * 3 + 1] = (float) (ground(level, pos, rxw, hint, rzw) - ay);
                right[j * 3 + 2] = (float) (rzw - az);
                normals[j * 2] = nx;
                normals[j * 2 + 1] = nz;
            }
            fill = new float[m * 12];
            for (int j = 0; j < m; j++) {
                System.arraycopy(left, j * 3, fill, j * 12, 3);
                System.arraycopy(right, j * 3, fill, j * 12 + 3, 3);
                System.arraycopy(right, (j + 1) * 3, fill, j * 12 + 6, 3);
                System.arraycopy(left, (j + 1) * 3, fill, j * 12 + 9, 3);
            }
            edges.add(new Edge(left, normals, false));
            edges.add(new Edge(right, normals, false));
            edges.add(cap(left, right, 0, dirX, dirZ));
            edges.add(cap(left, right, m, dirX, dirZ));
        } else {
            final double r = e.radius();
            if (r <= 0.05) {
                return null;
            }
            final int n = FxLook.ringSegments(r);
            final float[] ring = new float[n * 3];
            final float[] normals = new float[n * 2];
            for (int i = 0; i < n; i++) {
                final double angle = Math.PI * 2 * i / n;
                final double x = e.x() + Math.cos(angle) * r;
                final double z = e.z() + Math.sin(angle) * r;
                ring[i * 3] = (float) (x - ax);
                ring[i * 3 + 1] = (float) (ground(level, pos, x, e.y(), z) - ay);
                ring[i * 3 + 2] = (float) (z - az);
                normals[i * 2] = (float) Math.cos(angle);
                normals[i * 2 + 1] = (float) Math.sin(angle);
            }
            edges.add(new Edge(ring, normals, true));
            if (e.shape() == FxCodec.SHAPE_AREA) {
                fill = disc(level, pos, e, Math.min(n, 64));
            }
        }
        return new Ground(ax, ay, az, fill, edges, e.style(), e.important(), ticks);
    }

    /** 線的一頭：從左邊到右邊一小段外框。 */
    private static Edge cap(final float[] left, final float[] right, final int j, final float dirX, final float dirZ) {
        final float[] points = {left[j * 3], left[j * 3 + 1], left[j * 3 + 2], right[j * 3], right[j * 3 + 1], right[j * 3 + 2]};
        return new Edge(points, new float[] {dirX, dirZ, dirX, dirZ}, false);
    }

    /** 圓盤：一圈一圈往外，每格量一次高度；四邊形（中間那圈是退化的三角形）。 */
    private static float[] disc(final ClientLevel level, final BlockPos.MutableBlockPos pos, final FxCodec.Effect e, final int segments) {
        final int rings = FxLook.discRings(e.radius());
        final float[][] grid = new float[rings + 1][segments * 3];
        for (int k = 0; k <= rings; k++) {
            final double rk = e.radius() * k / rings;
            for (int i = 0; i < segments; i++) {
                final double angle = Math.PI * 2 * i / segments;
                final double x = e.x() + Math.cos(angle) * rk;
                final double z = e.z() + Math.sin(angle) * rk;
                final float h = k == 0 && i > 0 ? grid[0][1] + (float) e.y() : (float) ground(level, pos, x, e.y(), z);
                grid[k][i * 3] = (float) (x - e.x());
                grid[k][i * 3 + 1] = h - (float) e.y();
                grid[k][i * 3 + 2] = (float) (z - e.z());
            }
        }
        final float[] quads = new float[rings * segments * 12];
        int o = 0;
        for (int k = 0; k < rings; k++) {
            for (int i = 0; i < segments; i++) {
                final int next = (i + 1) % segments;
                o = put(quads, o, grid[k], i);
                o = put(quads, o, grid[k + 1], i);
                o = put(quads, o, grid[k + 1], next);
                o = put(quads, o, grid[k], next);
            }
        }
        return quads;
    }

    private static int put(final float[] out, final int o, final float[] ring, final int i) {
        out[o] = ring[i * 3];
        out[o + 1] = ring[i * 3 + 1];
        out[o + 2] = ring[i * 3 + 2];
        return o + 3;
    }

    /**
     * (x,z) 這裡的地面高度：從 hint 上面一格往下找 5 格，第一個有碰撞箱（或有水、岩漿）的方塊的頂；找不到（懸空、還沒載入）用 hint。
     */
    static double ground(final ClientLevel level, final BlockPos.MutableBlockPos pos, final double x, final double hint, final double z) {
        final int bx = Mth.floor(x);
        final int bz = Mth.floor(z);
        final int top = Mth.floor(hint + 1.0);
        for (int by = top; by >= top - 4; by--) {
            pos.set(bx, by, bz);
            final BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                continue;
            }
            final FluidState fluid = state.getFluidState();
            double height = Double.NaN;
            if (!fluid.isEmpty()) {
                height = by + fluid.getHeight(level, pos);
            }
            final VoxelShape shape = state.getCollisionShape(level, pos);
            if (!shape.isEmpty()) {
                final double solid = by + shape.max(Direction.Axis.Y);
                height = Double.isNaN(height) ? solid : Math.max(height, solid);
            }
            if (!Double.isNaN(height) && height <= hint + 1.05) {
                return height;
            }
        }
        return hint;
    }
}
