package com.vishnu.pdf_studio_api.pdfstudioapi.aspects;

import com.vishnu.pdf_studio_api.pdfstudioapi.annotation.ValidateUpload;
import com.vishnu.pdf_studio_api.pdfstudioapi.validation.UploadValidator;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Enforces {@link ValidateUpload} by inspecting the endpoint's own {@code @RequestPart} parameters.
 *
 * <p>Rather than requiring each endpoint to restate its part names, the aspect reads them from the
 * method signature — so a renamed part cannot drift out of sync with its validation rule.
 *
 * <p>Ordered ahead of {@code ChargeCreditsAspect} so a malformed upload is rejected with a 4xx
 * <b>before</b> any balance check or debit happens: a user must never spend a credit on a file that
 * was never going to be processed.
 */
@Aspect
@Component
@Order(0)
@RequiredArgsConstructor
public class ValidateUploadAspect {

    private final UploadValidator validator;

    @Before("@annotation(validateUpload)")
    public void validate(JoinPoint joinPoint, ValidateUpload validateUpload) {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        Annotation[][] parameterAnnotations = method.getParameterAnnotations();
        Object[] args = joinPoint.getArgs();
        Set<String> imageParts = Set.of(validateUpload.imageParts());

        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            String partName = partNameOf(parameterAnnotations[i], method, i);

            // A part is an image when the endpoint says so explicitly, or when the whole endpoint
            // deals in images. Everything else is expected to be a PDF.
            boolean isImage = imageParts.contains(partName)
                    || validateUpload.value() == ValidateUpload.Kind.IMAGE;

            if (arg instanceof MultipartFile single) {
                if (isImage) validator.image(single, partName);
                else validator.pdf(single, partName);
                continue;
            }

            List<MultipartFile> files = filesIn(arg);
            if (files == null) continue; // not a file parameter — the request DTO, etc.

            if (isImage) validator.images(files, validateUpload.minFiles(), partName);
            else validator.pdfs(files, validateUpload.minFiles(), partName);
        }
    }

    /**
     * @return the files held by a collection argument, or {@code null} when the argument is not a
     *         collection of {@link MultipartFile} (so non-file parameters are skipped).
     */
    private List<MultipartFile> filesIn(Object arg) {
        if (!(arg instanceof Collection<?> collection) || collection.isEmpty()) return null;
        List<MultipartFile> files = new ArrayList<>(collection.size());
        for (Object item : collection) {
            if (!(item instanceof MultipartFile file)) return null;
            files.add(file);
        }
        return files;
    }

    /** Reads the declared {@code @RequestPart} name, falling back to the parameter's own name. */
    private String partNameOf(Annotation[] annotations, Method method, int index) {
        for (Annotation annotation : annotations) {
            if (annotation instanceof RequestPart part) {
                if (!part.value().isBlank()) return part.value();
                if (!part.name().isBlank()) return part.name();
            }
        }
        return method.getParameters()[index].getName();
    }
}
