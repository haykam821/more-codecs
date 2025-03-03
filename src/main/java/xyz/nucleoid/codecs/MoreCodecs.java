package xyz.nucleoid.codecs;

import com.google.common.base.Preconditions;
import com.google.gson.JsonElement;
import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtOps;
import net.minecraft.predicate.BlockPredicate;
import net.minecraft.recipe.Ingredient;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.Util;
import net.minecraft.util.dynamic.Codecs;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ColumnPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.gen.stateprovider.BlockStateProvider;
import net.minecraft.world.gen.stateprovider.SimpleBlockStateProvider;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.IntStream;

public final class MoreCodecs {
    public static final Codec<ItemStack> ITEM_STACK = Codec.either(ItemStack.CODEC, Registries.ITEM.getCodec())
            .xmap(either -> either.map(Function.identity(), ItemStack::new), Either::left);

    public static final Codec<BlockState> BLOCK_STATE = Codec.either(BlockState.CODEC, Registries.BLOCK.getCodec())
            .xmap(either -> either.map(Function.identity(), Block::getDefaultState), Either::left);

    public static final Codec<BlockStateProvider> BLOCK_STATE_PROVIDER = Codec.either(BlockStateProvider.TYPE_CODEC, BLOCK_STATE)
            .xmap(either -> either.map(Function.identity(), SimpleBlockStateProvider::of), Either::left);

    /**
     * @deprecated Use {@link EquipmentSlot#CODEC}
     */
    @Deprecated
    public static final Codec<EquipmentSlot> EQUIPMENT_SLOT = EquipmentSlot.CODEC;

    /**
     * @deprecated Use {@link BlockPredicate#CODEC}
     */
    @Deprecated
    public static final Codec<BlockPredicate> BLOCK_PREDICATE = BlockPredicate.CODEC;

    public static final Codec<Box> BOX = RecordCodecBuilder.create(instance -> instance.group(
            Vec3d.CODEC.fieldOf("min").forGetter(box -> new Vec3d(box.minX, box.minY, box.minZ)),
            Vec3d.CODEC.fieldOf("max").forGetter(box -> new Vec3d(box.maxX, box.maxY, box.maxZ))
    ).apply(instance, Box::new));

    /**
     * @deprecated Use {@link Ingredient#ALLOW_EMPTY_CODEC}
     */
    @Deprecated
    public static final Codec<Ingredient> INGREDIENT = Ingredient.ALLOW_EMPTY_CODEC;

    public static final Codec<URL> URL = Codec.STRING.comapFlatMap(string -> {
        try {
            return DataResult.success(new URL(string));
        } catch (MalformedURLException e) {
            return DataResult.error(() -> {
                return "Malformed URL: " + e.getMessage();
            });
        }
    }, java.net.URL::toString);

    public static final Codec<ColumnPos> COLUMN_POS = Codec.INT_STREAM
            .comapFlatMap(
                    stream -> Util.decodeFixedLengthArray(stream, 2).map(values -> new ColumnPos(values[0], values[1])),
                    pos -> IntStream.of(pos.x(), pos.z())
            )
            .stable();

    /**
     * @param protocol the case-insensitive protocol to match
     */
    public static Codec<URL> url(String protocol) {
        Preconditions.checkNotNull(protocol);

        return validate(URL, url -> {
            if (protocol.equalsIgnoreCase(url.getProtocol())) {
                return DataResult.success(url);
            } else {
                return DataResult.error(() -> {
                    return "Expected protocol '" + protocol + "' but found '" + url.getProtocol() + "'";
                });
            }
        });
    }

    public static <T> Codec<T[]> arrayOrUnit(Codec<T> codec, IntFunction<T[]> factory) {
        return listToArray(listOrUnit(codec), factory);
    }

    public static <T> Codec<List<T>> listOrUnit(Codec<T> codec) {
        return Codec.either(codec.listOf(), codec)
                .xmap(
                        either -> either.map(Function.identity(), MoreCodecs::unitArrayList),
                        list -> list.size() == 1 ? Either.right(list.get(0)) : Either.left(list)
                );
    }

    public static <T> Codec<T[]> listToArray(Codec<List<T>> codec, IntFunction<T[]> factory) {
        return codec.xmap(list -> list.toArray(factory.apply(0)), Arrays::asList);
    }

    public static <A> Codec<A> stringVariants(A[] values, Function<A, String> asName) {
        return keyedVariants(values, asName, Codec.STRING);
    }

    public static <A, K> Codec<A> keyedVariants(A[] values, Function<A, K> asKey, Codec<K> keyCodec) {
        Map<K, A> byKey = new Object2ObjectOpenHashMap<>();
        for (A value : values) {
            byKey.put(asKey.apply(value), value);
        }

        return keyCodec.comapFlatMap(key -> {
            A value = byKey.get(key);
            return value != null ? DataResult.success(value) : DataResult.error(() -> "No variant with key '" + key + "'");
        }, asKey);
    }

    public static <A> Codec<A> withJson(Function<A, JsonElement> encode, Function<JsonElement, DataResult<A>> decode) {
        return Codecs.JSON_ELEMENT.comapFlatMap(decode, encode);
    }

    public static <A> Codec<A> withNbt(Function<A, NbtElement> encode, Function<NbtElement, DataResult<A>> decode) {
        return withOps(NbtOps.INSTANCE, encode, decode);
    }

    public static <A> Codec<A> withNbt(
            BiFunction<A, NbtCompound, NbtCompound> encode,
            BiConsumer<A, NbtCompound> decode,
            Supplier<A> factory
    ) {
        return withNbt(
                value -> encode.apply(value, new NbtCompound()),
                tag -> {
                    if (tag instanceof NbtCompound) {
                        A value = factory.get();
                        decode.accept(value, (NbtCompound) tag);
                        return DataResult.success(value);
                    }
                    return DataResult.error(() -> "Expected compound tag");
                }
        );
    }

    public static <A, T> Codec<A> withOps(DynamicOps<T> ops, Function<A, T> encode, Function<T, DataResult<A>> decode) {
        return Codec.PASSTHROUGH.comapFlatMap(
                input -> decode.apply(input.convert(ops).getValue()),
                value -> new Dynamic<>(ops, encode.apply(value))
        );
    }

    public static <K, V> Codec<Map<K, V>> dispatchByMapKey(Codec<K> keyCodec, Function<K, Codec<V>> valueCodec) {
        return new DispatchMapCodec<>(keyCodec, valueCodec);
    }

    /**
     * @deprecated Use {@link RegistryKey#createCodec}
     */
    @Deprecated
    public static <T> Codec<RegistryKey<T>> registryKey(RegistryKey<? extends Registry<T>> registry) {
        return RegistryKey.createCodec(registry);
    }

    /**
     * @deprecated Use {@link Codecs#validate}
     */
    @Deprecated
    public static <T> Codec<T> validate(Codec<T> codec, Function<T, DataResult<T>> validate) {
        return Codecs.validate(codec, validate);
    }

    public static <T> Codec<T> validate(Codec<T> codec, Predicate<T> validate, Supplier<String> error) {
        return Codecs.validate(codec, value -> {
            if (validate.test(value)) {
                return DataResult.success(value);
            } else {
                return DataResult.error(error);
            }
        });
    }

    private static <T> List<T> unitArrayList(T t) {
        List<T> list = new ArrayList<>(1);
        list.add(t);
        return list;
    }

    /**
     * Returns a {@link MapCodec} to decode the given {@link Codec} as a field. This functions very similar to
     * {@link Codec#optionalFieldOf(String, Object)}, however, when encountering an error, it will propagate this
     * instead of returning {@link Optional#empty()}. This is useful when failed parsing should be handled instead of
     * ignored.
     *
     * @param codec           the field codec to use
     * @param name            the name of the field to parse
     * @param defaultSupplier a supplier for a default value if not present
     * @param <T>             the codec parse type
     * @return a {@link MapCodec} that decodes the specified field
     */
    public static <T> MapCodec<T> propagatingOptionalFieldOf(Codec<T> codec, String name, Supplier<? extends T> defaultSupplier) {
        return Codecs.createStrictOptionalFieldCodec(codec, name)
                .xmap(opt -> opt.orElseGet(defaultSupplier), Optional::of);
    }

    /**
     * Returns a {@link MapCodec} to decode the given {@link Codec} as a field. This functions very similar to
     * {@link Codec#optionalFieldOf(String, Object)}, however, when encountering an error, it will propagate this
     * instead of returning {@link Optional#empty()}. This is useful when failed parsing should be handled instead of
     * ignored.
     *
     * @param codec        the field codec to use
     * @param name         the name of the field to parse
     * @param defaultValue a default value if not present
     * @param <T>          the codec parse type
     * @return a {@link MapCodec} that decodes the specified field
     *
     * @deprecated Use {@link Codecs#createStrictOptionalFieldCodec(Codec, String, Object)}, which additionally compares
     * values against the default value using {@link Objects#equals(Object, Object)}
     */
    @Deprecated
    public static <T> MapCodec<T> propagatingOptionalFieldOf(Codec<T> codec, String name, T defaultValue) {
        return Codecs.createStrictOptionalFieldCodec(codec, name)
                .xmap(opt -> opt.orElse(defaultValue), Optional::of);
    }
}
