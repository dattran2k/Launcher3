/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dat.launcher3.logging;

import android.util.ArrayMap;
import android.util.SparseArray;
import android.view.View;

import com.dat.launcher3.AppInfo;
import com.dat.launcher3.ButtonDropTarget;
import com.dat.launcher3.ItemInfo;
import com.dat.launcher3.LauncherSettings;
import com.dat.launcher3.util.InstantAppResolver;
import com.dat.launcher3.userevent.nano.LauncherLogExtensions;
import com.dat.launcher3.userevent.nano.LauncherLogProto;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Helper methods for logging.
 */
public class LoggerUtils {
    private static final ArrayMap<Class, SparseArray<String>> sNameCache = new ArrayMap<>();
    private static final String UNKNOWN = "UNKNOWN";

    public static String getFieldName(int value, Class c) {
        SparseArray<String> cache;
        synchronized (sNameCache) {
            cache = sNameCache.get(c);
            if (cache == null) {
                cache = new SparseArray<>();
                for (Field f : c.getDeclaredFields()) {
                    if (f.getType() == int.class && Modifier.isStatic(f.getModifiers())) {
                        try {
                            f.setAccessible(true);
                            cache.put(f.getInt(null), f.getName());
                        } catch (IllegalAccessException e) {
                            // Ignore
                        }
                    }
                }
                sNameCache.put(c, cache);
            }
        }
        String result = cache.get(value);
        return result != null ? result : UNKNOWN;
    }

    public static String getActionStr(LauncherLogProto.Action action) {
        String str = "";
        switch (action.type) {
            case LauncherLogProto.Action.Type.TOUCH:
                str += getFieldName(action.touch, LauncherLogProto.Action.Touch.class);
                if (action.touch == LauncherLogProto.Action.Touch.SWIPE || action.touch == LauncherLogProto.Action.Touch.FLING) {
                    str += " direction=" + getFieldName(action.dir, LauncherLogProto.Action.Direction.class);
                }
                return str;
            case LauncherLogProto.Action.Type.COMMAND: return getFieldName(action.command, LauncherLogProto.Action.Command.class);
            default: return getFieldName(action.type, LauncherLogProto.Action.Type.class);
        }
    }

    public static String getTargetStr(LauncherLogProto.Target t) {
        if (t == null){
            return "";
        }
        String str = "";
        switch (t.type) {
            case LauncherLogProto.Target.Type.ITEM:
                str = getItemStr(t);
                break;
            case LauncherLogProto.Target.Type.CONTROL:
                str = getFieldName(t.controlType, LauncherLogProto.ControlType.class);
                break;
            case LauncherLogProto.Target.Type.CONTAINER:
                str = getFieldName(t.containerType, LauncherLogProto.ContainerType.class);
                if (t.containerType == LauncherLogProto.ContainerType.WORKSPACE ||
                        t.containerType == LauncherLogProto.ContainerType.HOTSEAT) {
                    str += " id=" + t.pageIndex;
                } else if (t.containerType == LauncherLogProto.ContainerType.FOLDER) {
                    str += " grid(" + t.gridX + "," + t.gridY+ ")";
                }
                break;
            default:
                str += "UNKNOWN TARGET TYPE";
        }

        if (t.tipType != LauncherLogProto.TipType.DEFAULT_NONE) {
            str += " " + getFieldName(t.tipType, LauncherLogProto.TipType.class);
        }

        return str;
    }

    private static String getItemStr(LauncherLogProto.Target t) {
        String typeStr = getFieldName(t.itemType, LauncherLogProto.ItemType.class);
        if (t.packageNameHash != 0) {
            typeStr += ", packageHash=" + t.packageNameHash;
        }
        if (t.componentHash != 0) {
            typeStr += ", componentHash=" + t.componentHash;
        }
        if (t.intentHash != 0) {
            typeStr += ", intentHash=" + t.intentHash;
        }
        if ((t.packageNameHash != 0 || t.componentHash != 0 || t.intentHash != 0) &&
                t.itemType != LauncherLogProto.ItemType.TASK) {
            typeStr += ", predictiveRank=" + t.predictedRank + ", grid(" + t.gridX + "," + t.gridY
                    + "), span(" + t.spanX + "," + t.spanY
                    + "), pageIdx=" + t.pageIndex;

        }
        if (t.itemType == LauncherLogProto.ItemType.TASK) {
            typeStr += ", pageIdx=" + t.pageIndex;
        }
        return typeStr;
    }

    public static LauncherLogProto.Target newItemTarget(int itemType) {
        LauncherLogProto.Target t = newTarget(LauncherLogProto.Target.Type.ITEM);
        t.itemType = itemType;
        return t;
    }

    public static LauncherLogProto.Target newItemTarget(View v, InstantAppResolver instantAppResolver) {
        return (v.getTag() instanceof ItemInfo)
                ? newItemTarget((ItemInfo) v.getTag(), instantAppResolver)
                : newTarget(LauncherLogProto.Target.Type.ITEM);
    }

    public static LauncherLogProto.Target newItemTarget(ItemInfo info, InstantAppResolver instantAppResolver) {
        LauncherLogProto.Target t = newTarget(LauncherLogProto.Target.Type.ITEM);

        switch (info.itemType) {
            case LauncherSettings.Favorites.ITEM_TYPE_APPLICATION:
                t.itemType = (instantAppResolver != null && info instanceof AppInfo
                        && instantAppResolver.isInstantApp(((AppInfo) info)) )
                        ? LauncherLogProto.ItemType.WEB_APP
                        : LauncherLogProto.ItemType.APP_ICON;
                t.predictedRank = -100; // Never assigned
                break;
            case LauncherSettings.Favorites.ITEM_TYPE_SHORTCUT:
                t.itemType = LauncherLogProto.ItemType.SHORTCUT;
                break;
            case LauncherSettings.Favorites.ITEM_TYPE_FOLDER:
                t.itemType = LauncherLogProto.ItemType.FOLDER_ICON;
                break;
            case LauncherSettings.Favorites.ITEM_TYPE_APPWIDGET:
                t.itemType = LauncherLogProto.ItemType.WIDGET;
                break;
            case LauncherSettings.Favorites.ITEM_TYPE_DEEP_SHORTCUT:
                t.itemType = LauncherLogProto.ItemType.DEEPSHORTCUT;
                break;
        }
        return t;
    }

    public static LauncherLogProto.Target newDropTarget(View v) {
        if (!(v instanceof ButtonDropTarget)) {
            return newTarget(LauncherLogProto.Target.Type.CONTAINER);
        }
        if (v instanceof ButtonDropTarget) {
            return ((ButtonDropTarget) v).getDropTargetForLogging();
        }
        return newTarget(LauncherLogProto.Target.Type.CONTROL);
    }

    public static LauncherLogProto.Target newTarget(int targetType, LauncherLogExtensions.TargetExtension extension) {
        LauncherLogProto.Target t = new LauncherLogProto.Target();
        t.type = targetType;
        t.extension = extension;
        return t;
    }

    public static LauncherLogProto.Target newTarget(int targetType) {
        LauncherLogProto.Target t = new LauncherLogProto.Target();
        t.type = targetType;
        return t;
    }

    public static LauncherLogProto.Target newControlTarget(int controlType) {
        LauncherLogProto.Target t = newTarget(LauncherLogProto.Target.Type.CONTROL);
        t.controlType = controlType;
        return t;
    }

    public static LauncherLogProto.Target newContainerTarget(int containerType) {
        LauncherLogProto.Target t = newTarget(LauncherLogProto.Target.Type.CONTAINER);
        t.containerType = containerType;
        return t;
    }

    public static LauncherLogProto.Action newAction(int type) {
        LauncherLogProto.Action a = new LauncherLogProto.Action();
        a.type = type;
        return a;
    }

    public static LauncherLogProto.Action newCommandAction(int command) {
        LauncherLogProto.Action a = newAction(LauncherLogProto.Action.Type.COMMAND);
        a.command = command;
        return a;
    }

    public static LauncherLogProto.Action newTouchAction(int touch) {
        LauncherLogProto.Action a = newAction(LauncherLogProto.Action.Type.TOUCH);
        a.touch = touch;
        return a;
    }

    public static LauncherLogProto.LauncherEvent newLauncherEvent(LauncherLogProto.Action action, LauncherLogProto.Target... srcTargets) {
        LauncherLogProto.LauncherEvent event = new LauncherLogProto.LauncherEvent();
        event.srcTarget = srcTargets;
        event.action = action;
        return event;
    }
}
