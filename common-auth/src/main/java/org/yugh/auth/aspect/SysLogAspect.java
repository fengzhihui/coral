package org.yugh.auth.aspect;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;
import org.yugh.auth.annotation.Operation;
import org.yugh.auth.common.enums.ResultEnum;
import org.yugh.auth.exception.BusinessException;
import org.yugh.auth.pojo.bo.SysLogBO;
import org.yugh.auth.pojo.dto.User;
import org.yugh.auth.service.AuthService;
import org.yugh.auth.util.IpAddressUtil;
import org.yugh.auth.util.JsonUtils;

import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.util.Date;

/**
 * 日志记录
 *
 * @author yugenhai
 */
@Slf4j
@Aspect
@Component
public class SysLogAspect {

    @Autowired
    private AuthService authService;

    @Pointcut("@annotation(org.yugh.auth.annotation.Operation)")
    public void pointMethod() {
    }

    @Pointcut("@within(org.yugh.auth.annotation.Operation)")
    public void pointType() {
    }


    /**
     * 1:在类或方法上加 @Operation(value="操作的功能")
     * 2:在该方法执行完毕后执行本切面功能
     * 4:避开解析ServletRequest ServletResponse MultipartFile 等会产生递归栈溢出的对象
     * 3:只是参考日志记录，只做输出，微服务要单独声明并做保存
     *
     * @param joinPoint
     */
    @AfterReturning("pointMethod() || pointType()")
    public void printSysLog(JoinPoint joinPoint) {
        SysLogBO sysLogBO = new SysLogBO();
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        Operation operation = method.getAnnotation(Operation.class);
        if (operation != null) {
            String value = operation.value();
            sysLogBO.setOperation(value);
        }
        String className = joinPoint.getTarget().getClass().getName();
        String methodName = method.getName();
        sysLogBO.setMethod(className + "." + methodName);
        Object[] args = joinPoint.getArgs();
        Object[] arguments = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof ServletRequest || args[i] instanceof ServletResponse || args[i] instanceof MultipartFile) {
                continue;
            }
            arguments[i] = args[i];
        }
        String params = null;
        try {
            params = JsonUtils.toJson(arguments);
        } catch (Exception e) {
            log.error(" objectMapper.writeValueAsString Exception : {}", e.getMessage());
        }
        sysLogBO.setParams(params);
        sysLogBO.setCreateDate(new Date());
        HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest();
        Assert.notNull(((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest(), () -> "RequestContextHolder.getRequestAttributes() '" + ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest() + "' is null");
        sysLogBO.setIp(IpAddressUtil.getIpAddress(request));
        String token = authService.getTokenByHeader(request);
        if (StringUtils.isEmpty(token)) {
            log.info("saveSysLog --> User not login SSO, please Login!");
            throw new RuntimeException("User not login SSO, please Login To Gateway !");
        }
        boolean isValidate;
        try {
            isValidate = authService.validateToken(token);
        } catch (Exception e) {
            throw new BusinessException(ResultEnum.TOKEN_ILLEGAL);
        }
        if (!isValidate) {
            throw new BusinessException(ResultEnum.TOKEN_EXPIRED);
        } else {
            User user = authService.parseUserToJwt(request);
            if (StringUtils.isEmpty(user)) {
                throw new RuntimeException("Get User info exception");
            }
            sysLogBO.setUserNo(Long.valueOf(user.getNo()));
            sysLogBO.setUserName(user.getUserName());
            log.info("\n ******* Current User Operation Log |=| {}", sysLogBO.toString());
        }
    }
}