package com.spx.chat;

/** Pure geometry shared by drag, edge snapping and keyboard/window resize. */
final class CallFloatBounds {
    static int clamp(int value,int start,int end){return Math.max(start,Math.min(value,Math.max(start,end)));}
    static int edge(int x,int width,int left,int right){int end=Math.max(left,right-width);return x+width/2<(left+right)/2?left:end;}
}
