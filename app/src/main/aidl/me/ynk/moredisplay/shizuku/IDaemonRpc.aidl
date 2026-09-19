package me.ynk.moredisplay.shizuku;

interface IDaemonRpc {
    /**
     * 执行一次 RPC：payload 为 RpcCodec 序列化的 RpcRequest，返回序列化的 RpcResponse。
     */
    byte[] invoke(in byte[] payload);

    /**
     * 销毁服务：释放全部托管的虚拟显示器并退出进程。
     */
    void destroy();
}
