package com.sk89q.worldedit.internal.annotation;

import org.enginehub.piston.inject.InjectAnnotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * `//schem load` 的来源。可以是原理图目录里的相对路径、白名单里的 https 链接，或 `url:` 加网页剪贴板编号。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
@InjectAnnotation
public @interface SchematicSource {
}
